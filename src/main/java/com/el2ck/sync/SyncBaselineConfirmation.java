package com.el2ck.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 无需额外建表的短期预览凭证；不能跨任务/用户复用，也不能在预览后改变配置或目标水位。 */
final class SyncBaselineConfirmation {
    private static final long TTL_MS = 15 * 60 * 1000L;
    private SyncBaselineConfirmation() { }

    static String issue(String secret,String digest,long now) {
        String body=(now+TTL_MS)+":"+digest;
        return body+":"+sign(secret,body);
    }

    static void verify(String secret,String token,String currentDigest,long now) {
        try {
            String[] parts=token==null ? new String[0] : token.split(":",-1);
            if(parts.length!=3 || token.length()>256) throw new IllegalArgumentException();
            long expiry=Long.parseLong(parts[0]);
            if(!equal(sign(secret,parts[0]+":"+parts[1]),parts[2])) throw new IllegalArgumentException();
            if(expiry<now || expiry>now+TTL_MS || !equal(currentDigest,parts[1])) throw new IllegalArgumentException();
        } catch(RuntimeException e) {
            throw new IllegalStateException("预览已过期，或任务、断点、目标数据已变化，请重新预览后确认接管");
        }
    }

    private static boolean equal(String a,String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(String secret,String value) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception e) { throw new IllegalStateException("无法生成增量起点确认凭证",e); }
    }
}
