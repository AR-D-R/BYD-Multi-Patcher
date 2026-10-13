package okhttp3;

import okio.BufferedSource;

public abstract class ResponseBody {
    public static ResponseBody create(MediaType contentType, long contentLength, BufferedSource source) { return null; }
}
