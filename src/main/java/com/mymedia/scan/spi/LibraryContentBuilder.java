package com.mymedia.scan.spi;

import com.mymedia.library.LibraryDomain;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;

/**
 * 领域模块用来把物理文件构建成语义结构的服务提供接口。
 *
 * <p>{@code scan} 模块只定义本接口，不依赖任何具体领域模块；各领域自行实现
 * 它并注册为 Spring bean。
 */
public interface LibraryContentBuilder {

    /** 本实现负责哪个域。 */
    boolean supports(LibraryDomain domain);

    /** 发现新文件时构建语义条目。 */
    void onFileDiscovered(ScannedFileDiscovered event);

    /** 文件消失时把语义条目标记为不可用，不删除条目。 */
    void onFileVanished(ScannedFileVanished event);
}
