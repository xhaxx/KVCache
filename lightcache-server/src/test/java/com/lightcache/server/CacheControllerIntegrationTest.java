package com.lightcache.server;

import com.lightcache.core.CacheEngine;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * CacheController 集成测试。
 * <p>
 * 验证所有 REST API 端点在 Spring Boot 容器中的行为。
 * 每个测试方法执行前自动清空缓存，保证统计断言独立性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("CacheController 集成测试")
class CacheControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheEngine cacheEngine;

    @BeforeEach
    void clearCache() {
        // 保证每个测试独立，统计不互相污染
        cacheEngine.clear();
    }

    // ========== PUT ==========

    @Nested
    @DisplayName("PUT /cache/{key} — 键值写入")
    class PutEndpoint {

        @Test
        @DisplayName("写入字符串值并返回 200 ok")
        void putStringValue() throws Exception {
            mockMvc.perform(put("/cache/user_name")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"凯健\",\"ttl\":60}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }

        @Test
        @DisplayName("写入数值类型")
        void putNumericValue() throws Exception {
            mockMvc.perform(put("/cache/count")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":42,\"ttl\":60}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("覆盖已存在的 key")
        void overwriteKey() throws Exception {
            mockMvc.perform(put("/cache/overwrite_test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"v1\",\"ttl\":60}"));
            mockMvc.perform(put("/cache/overwrite_test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"v2\",\"ttl\":60}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/cache/overwrite_test"))
                    .andExpect(jsonPath("$.value").value("v2"));
        }
    }

    // ========== GET ==========

    @Nested
    @DisplayName("GET /cache/{key} — 键值读取")
    class GetEndpoint {

        @Test
        @DisplayName("命中时返回 200 和值")
        void getExistingKey() throws Exception {
            mockMvc.perform(put("/cache/read_test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"hello\",\"ttl\":300}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/cache/read_test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.key").value("read_test"))
                    .andExpect(jsonPath("$.value").value("hello"));
        }

        @Test
        @DisplayName("未命中时返回 404")
        void getNonExistentKey() throws Exception {
            mockMvc.perform(get("/cache/no_such_key_xyz"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not found"));
        }
    }

    // ========== DELETE ==========

    @Nested
    @DisplayName("DELETE /cache/{key} — 键删除")
    class DeleteEndpoint {

        @Test
        @DisplayName("删除存在的 key 返回 200")
        void deleteExistingKey() throws Exception {
            mockMvc.perform(put("/cache/to_delete")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"tmp\",\"ttl\":60}"))
                    .andExpect(status().isOk());

            mockMvc.perform(delete("/cache/to_delete"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));

            // 确认已删除
            mockMvc.perform(get("/cache/to_delete"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("删除不存在的 key 返回 404")
        void deleteNonExistentKey() throws Exception {
            mockMvc.perform(delete("/cache/ghost_key_404"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not found"));
        }
    }

    // ========== stats ==========

    @Nested
    @DisplayName("GET /cache/stats — 运行统计")
    class StatsEndpoint {

        @Test
        @DisplayName("统计接口包含必要字段")
        void statsReturnsExpectedFields() throws Exception {
            mockMvc.perform(get("/cache/stats"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hits").isNumber())
                    .andExpect(jsonPath("$.misses").isNumber())
                    .andExpect(jsonPath("$.size").isNumber())
                    .andExpect(jsonPath("$.hitRate").isNumber());
        }

        @Test
        @DisplayName("读写后命中统计正确")
        void statsReflectsHits() throws Exception {
            mockMvc.perform(put("/cache/stats_key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":1,\"ttl\":300}"));
            mockMvc.perform(get("/cache/stats_key")); // hit
            mockMvc.perform(get("/cache/stats_key")); // hit
            mockMvc.perform(get("/cache/nope"));      // miss

            mockMvc.perform(get("/cache/stats"))
                    .andExpect(jsonPath("$.hits").value(2))
                    .andExpect(jsonPath("$.misses").value(1));
        }

        @Test
        @DisplayName("size 反映当前键数")
        void statsSize() throws Exception {
            mockMvc.perform(put("/cache/s1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"a\",\"ttl\":300}"));
            mockMvc.perform(put("/cache/s2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"b\",\"ttl\":300}"));

            mockMvc.perform(get("/cache/stats"))
                    .andExpect(jsonPath("$.size").value(2));
        }
    }

    // ========== save ==========

    @Nested
    @DisplayName("POST /cache/save — 手动快照")
    class SaveEndpoint {

        @Test
        @DisplayName("手动触发 save 返回 200")
        void triggerSave() throws Exception {
            mockMvc.perform(post("/cache/save"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true));
        }
    }

    // ========== 边界场景 ==========

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("key 中包含冒号等特殊字符")
        void keyWithSpecialChars() throws Exception {
            String key = "user:1001:profile";
            mockMvc.perform(put("/cache/" + key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"special\",\"ttl\":60}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/cache/" + key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value("special"));
        }

        @Test
        @DisplayName("写入空字符串值")
        void emptyValue() throws Exception {
            mockMvc.perform(put("/cache/empty_val")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"\",\"ttl\":60}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/cache/empty_val"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value(""));
        }

        @Test
        @DisplayName("TTL 过期后 GET 返回 404（惰性删除）")
        void expiredKeyReturns404() throws Exception {
            mockMvc.perform(put("/cache/ephemeral")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"short_lived\",\"ttl\":1}"))
                    .andExpect(status().isOk());

            Thread.sleep(1100);

            mockMvc.perform(get("/cache/ephemeral"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("中文 key 和 value 正常处理")
        void chineseContent() throws Exception {
            mockMvc.perform(put("/cache/中文键")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"value\":\"中文值\",\"ttl\":300}"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/cache/中文键"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.value").value("中文值"));
        }
    }
}
