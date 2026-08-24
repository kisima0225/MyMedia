package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LibraryService {

    private final MediaLibraryRepository repository;
    private final JdbcTemplate jdbc;

    LibraryService(MediaLibraryRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Transactional
    public MediaLibrary create(String name, LibraryDomain domain, String rootPath) {
        return repository.saveAndFlush(new MediaLibrary(name, domain, rootPath));
    }

    @Transactional(readOnly = true)
    public MediaLibrary getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到媒体库 id=" + id));
    }

    @Transactional(readOnly = true)
    public List<MediaLibrary> findAll() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<MediaLibrary> findByDomain(LibraryDomain domain) {
        return repository.findByDomain(domain);
    }

    /**
     * 该库配置的刮削器名单，顺序即尝试顺序。
     *
     * <p>空数组表示<b>该库不刮削</b>：其条目直接置 {@code NOT_APPLICABLE}，
     * 连任务都不排，界面上零刮削噪音。
     *
     * <p>{@code metadata_providers} 是 {@code text[]}，按项目约定不做 JPA 映射，
     * 走 {@link JdbcTemplate} 直读直写。
     */
    @Transactional(readOnly = true)
    public List<String> metadataProvidersOf(Long libraryId) {
        return jdbc.queryForObject(
                "SELECT metadata_providers FROM libraries WHERE id = ?",
                (rs, rowNum) -> {
                    java.sql.Array array = rs.getArray(1);
                    return array == null ? List.<String>of() : List.of((String[]) array.getArray());
                }, libraryId);
    }

    /**
     * 覆盖该库的刮削器名单。
     *
     * <p>用 {@code createArrayOf} 而不是拼 {@code '{a,b}'} 字面量：后者对逗号、
     * 双引号、大括号都要自己转义，出错方式是静默写错数据而不是报错。
     */
    @Transactional
    public void setMetadataProviders(Long libraryId, List<String> providers) {
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "UPDATE libraries SET metadata_providers = ? WHERE id = ?");
            statement.setArray(1, connection.createArrayOf("text", providers.toArray(String[]::new)));
            statement.setLong(2, libraryId);
            return statement;
        });
    }
}
