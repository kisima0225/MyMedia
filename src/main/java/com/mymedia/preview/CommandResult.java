package com.mymedia.preview;

/** 一次外部进程调用的结果。 */
public record CommandResult(int exitCode, String stdout, String stderr) {

    public boolean succeeded() {
        return exitCode == 0;
    }
}
