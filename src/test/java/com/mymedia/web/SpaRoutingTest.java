package com.mymedia.web;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SpaRoutingTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    @Test
    void 根路径返回_index_html() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void 前端深层路由返回_index_html_而不是_404() throws Exception {
        // 用户在 /video/items/12 上按 F5，浏览器真的会向服务器要这个路径
        for (String route : new String[]{"/video", "/video/items/12", "/image/nodes/7/read",
                                         "/search", "/admin/libraries", "/s/abc123"}) {
            mockMvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/html"));
        }
    }

    @Test
    void api_下不存在的路径仍然是_404_不能被兜成_html() throws Exception {
        // 兜成 HTML 会让前端的错误处理拿到一坨 <!doctype html> 去 JSON.parse
        // 未认证请求会在到达静态资源兜底之前就被 anyRequest().authenticated() 拦成 401，
        // 所以这里要带上 Basic 认证，才能真正测到兜底 resolver 见到 api/ 前缀返回 null 这件事。
        String username = "spa-" + UUID.randomUUID();
        registrationService.register(username, "pw-" + username, UserRole.USER);

        mockMvc.perform(get("/api/definitely-not-a-real-endpoint")
                        .with(httpBasic(username, "pw-" + username)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 带扩展名的缺失静态文件是_404_不是_index_html() throws Exception {
        // 否则缺失的 js 会返回 HTML，浏览器报一个完全指错方向的语法错误
        mockMvc.perform(get("/assets/does-not-exist.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 静态资源不需要登录() throws Exception {
        // 登录页本身就是静态资源，要求登录才能拿到登录页是个死循环
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void favicon_不需要登录() throws Exception {
        // index.html 用 <link rel="icon" href="/favicon.svg"> 引用它，而浏览器取图标的
        // 请求不带 Authorization 头。不在 SecurityConfig 的白名单里，这个请求就会撞上
        // anyRequest().authenticated() 变成 401——每个页面（包括匿名的登录页与分享页）
        // 都白打一次失败请求。
        //
        // 断言的是「不是 401」而不是 200：favicon.svg 来自前端构建产物
        // （frontend/public → frontend/dist → target/classes/static），
        // -DskipFrontend=true 时它不存在，那时 404 才是正确答案。这条要钉住的是
        // 安全白名单，不是构建产物在不在。
        for (String path : new String[]{"/favicon.svg", "/favicon.ico"}) {
            mockMvc.perform(get(path))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("GET %s 不该要求登录", path)
                            .isNotEqualTo(401));
        }
    }
}
