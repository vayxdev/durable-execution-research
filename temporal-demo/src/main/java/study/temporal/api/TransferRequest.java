package study.temporal.api;

/** REST 请求体:{"from":"alice","to":"bob","amount":100} */
public record TransferRequest(String from, String to, int amount) {
}
