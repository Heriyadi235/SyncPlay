package com.xigaigai.syncglance.nav;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AmapLink 回调信封解密。
 *
 * 格式（Base64 解码后）：
 * <pre>
 *   [int idLen][id...][int keyLen][key...][nonce 12 字节][密文 + GCM tag]
 * </pre>
 *
 * 算法：AES-GCM，AAD = id，密钥 = 信封里自带的 key。
 *
 * 关键点：**密钥随每条消息传过来**，不是授权 token，
 * 所以拿到回调就能自己解，不用问高德要任何东西。
 *
 * 解出来如果只有 {"data": "..."} 一层，
 * 说明业务数据被字符串化了，还要再解一次（可能带引号或转义）。
 *
 * 参考：https://github.com/fw867/hypercar/blob/main/app/src/main/java/io/github/hypercar/navigation/LinkEnvelope.kt
 */
public final class LinkEnvelope {

    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private LinkEnvelope() { }

    /**
     * 解码一条回调。
     * @return 解密后的明文 JSON；失败返回 null
     */
    public static String decode(String raw) {
        if (raw == null) return null;
        try {
            if (raw.length() > MAX_BYTES * 2) return null;

            String content = raw.trim();

            // 有些版本外面还套一层 {"encrypted":true,"content":"..."}
            if (content.startsWith("{")) {
                JSONObject outer = new JSONObject(content);
                if (!outer.optBoolean("encrypted")) {
                    return content;      // 明文，直接用
                }
                content = outer.getString("content");
            }

            // 去掉空白再 base64 解码
            StringBuilder sb = new StringBuilder(content.length());
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (!Character.isWhitespace(c)) sb.append(c);
            }

            byte[] bytes = Base64.getDecoder().decode(sb.toString());
            if (bytes.length < 36 || bytes.length > MAX_BYTES) return null;

            ByteBuffer b = ByteBuffer.wrap(bytes);

            int idSize = b.getInt();
            if (idSize < 1 || idSize > 99 || b.remaining() < idSize + 4) return null;
            byte[] id = new byte[idSize];
            b.get(id);

            int keySize = b.getInt();
            if (!(keySize == 16 || keySize == 24 || keySize == 32)) return null;
            if (b.remaining() < keySize + 28) return null;
            byte[] key = new byte[keySize];
            b.get(key);

            byte[] nonce = new byte[12];
            b.get(nonce);

            byte[] cipherText = new byte[b.remaining()];
            b.get(cipherText);

            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(128, nonce));
                cipher.updateAAD(id);

                String plain = new String(cipher.doFinal(cipherText), "UTF-8");

                // 剥一层 {"data":"..."}
                try {
                    JSONObject j = new JSONObject(plain);
                    if (j.length() == 1 && j.has("data") && j.opt("data") instanceof String) {
                        String inner = j.getString("data");
                        // 可能反复套，最多剥 3 层
                        for (int i = 0; i < 3 && looksEscaped(inner); i++) {
                            inner = unescapeOnce(inner);
                        }
                        return inner;
                    }
                } catch (Exception ignored) { }

                return plain;

            } finally {
                java.util.Arrays.fill(key, (byte) 0);
            }

        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksEscaped(String s) {
        if (s == null) return false;
        String t = s.trim();
        return (t.startsWith("\"") && t.endsWith("\""))
                || t.startsWith("%7B") || t.startsWith("%5B")
                || t.contains("%22");
    }

    /** 剥一层引号 / URL 转义 */
    private static String unescapeOnce(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() > 1) {
            t = t.substring(1, t.length() - 1);
        }
        if (t.startsWith("%")) {
            try {
                t = java.net.URLDecoder.decode(t, "UTF-8");
            } catch (Exception ignored) { }
        }
        return t.replace("\\\"", "\"").replace("\\/", "/");
    }
}
