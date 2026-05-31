package study.restate.service;

/**
 * handler 的入参。Restate 用 Jackson 把 HTTP body 的 JSON 反序列化成它。
 * 调用时 body 形如:{"from":"alice","to":"bob","amount":100}
 */
public record TransferRequest(String from, String to, int amount) {
}
