package qunar.tc.qmq.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * @author keli.wang
 * @since 2017/8/20
 */
public class ActionLog {
    private static final Logger LOG = LoggerFactory.getLogger(ActionLog.class);

    private static final int PER_SEGMENT_FILE_SIZE = 1024 * 1024 * 1024;

    private final MessageStoreConfig config;
    private final LogManager logManager;
    private final MessageAppender<Action, MessageSequence> actionAppender = new ActionAppender();

    public ActionLog(final MessageStoreConfig config) {
        this.config = config;
        this.logManager = new LogManager(new File(config.getActionLogStorePath()), PER_SEGMENT_FILE_SIZE, config, new ActionLogSegmentValidator());
    }

    public synchronized PutMessageResult addAction(final Action action) {
        final AppendMessageResult<MessageSequence> result;
        LogSegment segment = logManager.latestSegment();
        if (segment == null) {
            segment = logManager.allocNextSegment();
        }

        if (segment == null) {
            return new PutMessageResult(PutMessageStatus.CREATE_MAPPED_FILE_FAILED, null);
        }

        result = segment.append(action, actionAppender);
        switch (result.getStatus()) {
            case SUCCESS:
                break;
            case END_OF_FILE:
                if (logManager.allocNextSegment() == null) {
                    return new PutMessageResult(PutMessageStatus.CREATE_MAPPED_FILE_FAILED, null);
                }
                return addAction(action);
            case MESSAGE_SIZE_EXCEEDED:
                return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, result);
            default:
                return new PutMessageResult(PutMessageStatus.UNKNOWN_ERROR, result);
        }

        return new PutMessageResult(PutMessageStatus.SUCCESS, result);
    }

    public boolean appendData(final long startOffset, final ByteBuffer data) {
        LogSegment segment = logManager.locateSegment(startOffset);
        if (segment == null) {
            segment = logManager.allocOrResetSegments(startOffset);
            fillPreBlank(segment, startOffset);
        }

        return segment.appendData(data);
    }

    private void fillPreBlank(LogSegment segment, long untilWhere) {
        final ByteBuffer buffer = ByteBuffer.allocate(9);
        buffer.putInt(MagicCode.ACTION_LOG_MAGIC_V1);
        buffer.put((byte) 2);
        buffer.putInt((int) (untilWhere % PER_SEGMENT_FILE_SIZE));
        segment.fillPreBlank(buffer, untilWhere);
    }

    public SelectSegmentBufferResult getMessageData(final long offset) {
        final LogSegment segment = logManager.locateSegment(offset);
        if (segment == null) {
            return null;
        }

        final int pos = (int) (offset % PER_SEGMENT_FILE_SIZE);
        return segment.selectSegmentBuffer(pos);
    }

    public ActionLogVisitor newVisitor(final long start) {
        return new ActionLogVisitor(logManager, start);
    }

    public long getMaxOffset() {
        return logManager.getMaxOffset();
    }

    public void flush() {
        logManager.flush();
    }

    public void close() {
        logManager.close();
    }

    public void clean() {
        logManager.deleteExpiredSegments(config.getLogRetentionMs());
    }

    private class ActionAppender implements MessageAppender<Action, MessageSequence> {
        private static final int MIN_RECORD_BYTES = 5; // 4 bytes magic + 1 byte record type
        private static final int MAX_BYTES = 1024 * 1024 * 10; // 10M

        private final ByteBuffer workingBuffer = ByteBuffer.allocate(MAX_BYTES);

        @Override
        public AppendMessageResult<MessageSequence> doAppend(long baseOffset, ByteBuffer targetBuffer, int freeSpace, Action action) {
            workingBuffer.clear();
            final int size = fillBuffer(workingBuffer, action);
            final long wroteOffset = baseOffset + targetBuffer.position();

            if (size != freeSpace && size + MIN_RECORD_BYTES > freeSpace) {
                workingBuffer.clear();
                workingBuffer.limit(freeSpace);
                workingBuffer.putInt(MagicCode.ACTION_LOG_MAGIC_V1);
                workingBuffer.put((byte) 1);
                targetBuffer.put(workingBuffer.array(), 0, freeSpace);
                return new AppendMessageResult<>(AppendMessageStatus.END_OF_FILE, wroteOffset, freeSpace, null);
            } else {
                workingBuffer.limit(size);
                targetBuffer.put(workingBuffer.array(), 0, size);

                return new AppendMessageResult<>(AppendMessageStatus.SUCCESS, wroteOffset, size, new MessageSequence(wroteOffset, wroteOffset));
            }
        }

        private int fillBuffer(final ByteBuffer buffer, final Action action) {
            final int startIndex = buffer.position();
            buffer.putInt(MagicCode.ACTION_LOG_MAGIC_V1);
            buffer.put((byte) 0);
            buffer.put(action.type().getCode());

            final int payloadSizeIndex = buffer.position();
            buffer.position(buffer.position() + Integer.BYTES);
            final int payloadSize = action.type().getReaderWriter().write(buffer, action);
            buffer.putInt(payloadSizeIndex, payloadSize);

            return buffer.position() - startIndex;
        }
    }

    private class ActionLogSegmentValidator implements LogSegmentValidator {
        @Override
        public ValidateResult validate(LogSegment segment) {
            final int fileSize = segment.getFileSize();
            final ByteBuffer buffer = segment.sliceByteBuffer();

            int position = 0;
            while (true) {
                if (position == fileSize) {
                    return new ValidateResult(ValidateStatus.COMPLETE, fileSize);
                }

                final int result = consumeAndValidateMessage(buffer);
                if (result == -1) {
                    return new ValidateResult(ValidateStatus.PARTIAL, position);
                } else if (result == 0) {
                    return new ValidateResult(ValidateStatus.COMPLETE, fileSize);
                } else {
                    position += result;
                }
            }
        }

        private int consumeAndValidateMessage(final ByteBuffer buffer) {
            final int magic = buffer.getInt();
            if (magic != MagicCode.ACTION_LOG_MAGIC_V1) {
                return -1;
            }

            final byte recordType = buffer.get();
            if (recordType == 2) {
                return buffer.getInt();
            } else if (recordType == 1) {
                return 0;
            } else if (recordType == 0) {
                try {
                    final ActionType payloadType = ActionType.fromCode(buffer.get());
                    final int payloadSize = buffer.getInt();
                    payloadType.getReaderWriter().read(buffer);
                    return payloadSize + 10;
                } catch (Exception e) {
                    LOG.error("fail read action log", e);
                    return -1;
                }
            } else {
                return -1;
            }
        }
    }
}
