package com.mymedia.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * 托管 Vite 构建产物，并把前端路由兜回 {@code index.html}。
 *
 * <p>Vue Router 走 history 模式，{@code /video/items/12} 是一个<b>前端</b>路由。
 * 用户在这个地址上刷新，浏览器会向服务器发起真实的 GET——服务器上没有这个资源，
 * 默认 404，页面白屏。兜底让所有前端路由都拿到同一份 {@code index.html}，
 * 由前端路由接管。
 *
 * <p>静态资源处理的优先级是 {@code LOWEST_PRECEDENCE - 1}，排在所有
 * {@code @RestController} 之后，所以真实的 API 端点永远先被匹配到。
 * 兜底里那条 {@code api/} 判断管的是<b>不存在的</b> API 路径——它们必须
 * 老实返回 404，兜成 HTML 会让前端的错误处理拿到一坨
 * {@code <!doctype html>} 去 {@code JSON.parse}，报出的错完全指错方向。
 *
 * <p>拆成两个 resource handler 是为了缓存策略不能一刀切：Vite 给
 * {@code assets/} 下的产物带内容哈希，文件名随内容变，可以放心长缓存；
 * 但兜底返回的 {@code index.html} 不带哈希，是它告诉浏览器当前该找哪些
 * 哈希文件名的入口，一旦被长缓存，发一次新版后浏览器还攥着旧的
 * {@code index.html} 去请求早已不存在的哈希文件，白屏且无法自愈。
 *
 * <p><b>根路径 {@code /} 单独处理</b>：Spring MVC 的资源处理器（无论是这里注册的
 * 还是 Spring Boot 自带的）在收到「资源路径为空串」时会直接 404，压根不会走到
 * 下面的兜底 resolver——这不是优先级问题，是 {@code ResourceHttpRequestHandler}
 * 自身的保护逻辑。Spring Boot 为此专门准备了 {@code WelcomePageHandlerMapping}：
 * 发现 classpath 下有 {@code static/index.html} 就把 GET {@code /} 处理成
 * {@code forward:index.html}。生产环境下这个内部转发没问题，但 MockMvc 的
 * {@code MockRequestDispatcher} 只记录 {@code forwardedUrl}、并不真的重新分发，
 * 于是测试里看到状态码 200 但 {@code Content-Type} 是 null。
 * 解法是下面这个 {@link RootIndexController}：一个真正的 {@code @RequestMapping}，
 * 其所在的 {@code RequestMappingHandlerMapping} 固定 order 是 0，
 * 早于 Boot 欢迎页处理器的 order 2，所以它总是先被匹配到，
 * 一次分发内直接把 {@code index.html} 的内容和本类同款的 {@code Cache-Control: no-cache}
 * 写回去，不再依赖 forward 语义，Boot 的欢迎页处理器因此被自然短路、永远不会触发。
 */
@Configuration
class SpaWebConfig implements WebMvcConfigurer {

    private static final String STATIC_ROOT = "classpath:/static/";
    private static final String INDEX = "/static/index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite 把带内容哈希的产物放进 assets/：文件名随内容变，可以放心长缓存。
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(STATIC_ROOT + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());

        // 其余一切走兜底。index.html 不带哈希，绝不能长缓存——
        // 否则发一次新版，浏览器还会拿着旧外壳去请求早已不存在的哈希文件名。
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_ROOT)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            // 不存在的 API 路径：老实 404
            if (resourcePath.startsWith("api/")) {
                return null;
            }
            // 带扩展名的缺失文件：老实 404。否则缺失的 .js 会返回 HTML，
            // 浏览器报一个「Unexpected token '<'」，排查方向被彻底带偏。
            if (hasExtension(resourcePath)) {
                return null;
            }
            return new ClassPathResource(INDEX);
        }

        private static boolean hasExtension(String resourcePath) {
            int lastSlash = resourcePath.lastIndexOf('/');
            return resourcePath.indexOf('.', lastSlash + 1) >= 0;
        }
    }

    /**
     * 专门接管 GET {@code /}，见类注释——资源处理器天生服务不了根路径，
     * Boot 自带的欢迎页处理器又走 forward，在真实浏览器里没问题，
     * 但没法在 MockMvc 里验证到正确的 {@code Content-Type}。
     * 这里一次分发内直接把 index.html 写回去，缓存策略与 {@code /**} 兜底一致。
     */
    @RestController
    static class RootIndexController {

        @GetMapping("/")
        ResponseEntity<Resource> index() {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .cacheControl(CacheControl.noCache())
                    .body(new ClassPathResource(INDEX));
        }
    }
}
