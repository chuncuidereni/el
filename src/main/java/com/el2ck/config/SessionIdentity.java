package com.el2ck.config;
/**
 * 【文件职责】表示已登录会话中的最小用户身份快照，用于后续请求校验。
 */

import java.io.Serializable;
/** No passwords are stored in the session. */
public final class SessionIdentity implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long userId;
    public final long version;
    public final long createdAt;

    public SessionIdentity(long userId, long version) {
        this.userId = userId; this.version = version; this.createdAt = System.currentTimeMillis();
    }
}
