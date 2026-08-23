package com.mymedia.preview;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 用 {@link ProcessBuilder} 执行外部命令。
 *
 * <p>两个必须处理的坑：
 * <ol>
 *   <li><b>管道必须被读走。</b> ffmpeg 往 stderr 写进度，管道缓冲区满了它就会阻塞，
 *       表现为"卡死"。这里各起一个线程把两个流读干。虚拟线程让这个代价可以忽略。</li>
 *   <li><b>超时必须真的杀进程。</b> 只 {@code waitFor(timeout)} 而不 destroy，
 *       僵死的 ffmpeg 会一直占着任务租约。</li>
 * </ol>
 */
@Component
class ProcessCommandRunner implements CommandRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessCommandRunner.class);

    @Override
    public CommandResult run(List<String> command, Duration timeout)
            throws IOException, InterruptedException {

        log.debug("执行外部命令: {}", String.join(" ", command));

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            throw new IOException("无法启动外部进程: " + String.join(" ", command), e);
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = drain(process.getInputStream(), stdout);
        Thread errReader = drain(process.getErrorStream(), stderr);

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            outReader.join();
            errReader.join();
            throw new IOException("外部进程超时（" + timeout + "），已强制终止: "
                    + String.join(" ", command));
        }

        outReader.join();
        errReader.join();
        return new CommandResult(process.exitValue(), stdout.toString(), stderr.toString());
    }

    private static Thread drain(InputStream stream, StringBuilder sink) {
        return Thread.ofVirtual().start(() -> {
            try (InputStream in = stream) {
                sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // 进程被强杀时读流会断，这不是错误
            }
        });
    }
}