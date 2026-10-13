package okhttp3;

public class Response {
    public static class Builder {
        public Builder() {}
        public Builder request(Request request) { return this; }
        public Builder protocol(Protocol protocol) { return this; }
        public Builder code(int code) { return this; }
        public Builder message(String message) { return this; }
        public Builder header(String name, String value) { return this; }
        public Builder body(ResponseBody body) { return this; }
        public Response build() { return null; }
    }
}
