package interview.guide.modules.knowledgebase.model;

/**
 * 消息完成状态枚举
 */
public enum MessageStatus {
    COMPLETED,
    INSUFFICIENT_INFO,
    NO_RESULTS,
    MODEL_FAILED,
    CLIENT_DISCONNECTED
}
