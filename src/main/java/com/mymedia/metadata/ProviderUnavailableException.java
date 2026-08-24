package com.mymedia.metadata;

/**
 * 提供者暂时不可用：网络故障、超时、被限流。
 *
 * <p><b>与"没找到"严格区分。</b>没找到是正常状态（{@code NO_MATCH}，安静回落，
 * 界面不显示为错误）；不可用才置 {@code ERROR} 并按退避重试。把两者混为一谈
 * 会让一个冷门条目在任务表里永远重试下去。
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
