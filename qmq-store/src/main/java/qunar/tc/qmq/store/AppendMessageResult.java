package qunar.tc.qmq.store;

/**
 * @author keli.wang
 * @since 2017/7/4
 */
public class AppendMessageResult<T> {
    private final AppendMessageStatus status;
    private final long wroteOffset;
    private final int wroteBytes;
    private final T additional;

    public AppendMessageResult(AppendMessageStatus status) {
        this(status, 0, 0, null);
    }

    public AppendMessageResult(AppendMessageStatus status, long wroteOffset, int wroteBytes) {
        this(status, wroteOffset, wroteBytes, null);
    }

    public AppendMessageResult(AppendMessageStatus status, long wroteOffset, int wroteBytes, T additional) {
        this.status = status;
        this.wroteOffset = wroteOffset;
        this.wroteBytes = wroteBytes;
        this.additional = additional;
    }

    public AppendMessageStatus getStatus() {
        return status;
    }

    public long getWroteOffset() {
        return wroteOffset;
    }

    public int getWroteBytes() {
        return wroteBytes;
    }

    public T getAdditional() {
        return additional;
    }
}
