package com.mymedia.preview;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * 外部进程调用的唯一入口。
 *
 * <p><b>这个接口存在的全部理由是可测试性。</b> ffmpeg / ffprobe 烘焙在应用镜像里，
 * 开发机上不一定装了；把进程调用收敛到一个可注入的接口后，任务处理器的集成测试
 * 只需提供一个按输出路径写假文件的桩实现，就能在任何机器上跑。
 *
 * <p>任何直接 {@code new ProcessBuilder(...)} 的代码都会让这条路走不通。
 */
public interface CommandRunner {

    /**
     * 同步执行命令并等待结束。
     *
     * @throws IOException 进程无法启动，或超过 {@code timeout} 仍未结束（此时进程已被强制终止）
     */
    CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
}
