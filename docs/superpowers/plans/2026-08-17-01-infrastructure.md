# MyMedia 实施计划 01：基础设施

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 产出一个可启动的 Spring Boot 服务，具备账号认证、媒体库管理（含域分区的数据库级强制约束）、以及基于 PostgreSQL `SKIP LOCKED` 的持久化任务队列。

**Architecture:** Spring Modulith 模块化单体。模块边界由 `ApplicationModules.verify()` 架构测试强制，跨模块异步协作走领域事件。所有 schema 变更经 Flyway 版本化迁移，集成测试用 Testcontainers 跑真实 PostgreSQL。

**Tech Stack:** Spring Boot 4.1.0 · Java 25 · Spring Modulith 2.1.0 · PostgreSQL 17 · Flyway · Testcontainers 2.x · Maven

**Spec:** `docs/superpowers/specs/2026-08-17-mymedia-design.md`（覆盖 §3 技术选型、§4 架构、§5 领域分区、§6.2 共享表、§7.5 任务队列、§7.7 搜索、路线图 P0–P2）

---

## Global Constraints

以下约束对本计划**每一个任务**都生效，不再逐条重复。

### 版本坐标（已实测，禁止凭记忆更改）

| 组件 | 精确值 |
|---|---|
| `spring-boot-starter-parent` | **`4.1.0`** |
| `java.version` | `25` |
| `spring-modulith.version` | `2.1.0` |
| PostgreSQL 镜像 | `postgres:17` |

**⚠ `4.1.0.RELEASE` 是 Spring Initializr 的内部版本 ID，不是 Maven 坐标。** 写进 pom 会导致：
```
Could not find artifact org.springframework.boot:spring-boot-starter-parent:pom:4.1.0.RELEASE
```
从 start.spring.io 下载的骨架必须先改成 `4.1.0`。

### Spring Boot 4.x 的 artifact 改名（已实测）

绝大多数教程和训练数据仍是 3.x。**照记忆写会全部解析失败。**

| 3.x 写法（错误） | 4.1 正确写法 |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `flyway-core` | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` |
| `spring-boot-starter-test` | 拆分为 `spring-boot-starter-webmvc-test`、`-data-jpa-test`、`-security-test`、`-validation-test`、`-actuator-test`、`-flyway-test` |
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |
| `org.testcontainers.containers.PostgreSQLContainer`（包名） | `org.testcontainers.postgresql.PostgreSQLContainer` |
| `PostgreSQLContainer<?>`（泛型） | `PostgreSQLContainer`（**非泛型，不带类型参数**） |

### 验证纪律

- **绝不用 `cmd \| tail` 判断构建是否成功**——管道会吞掉 Maven 的退出码。必须 `mvn ... > build.log 2>&1; echo "EXIT=$?"`，确认 `EXIT=0` 且日志含 `BUILD SUCCESS`。
- 查 Maven 构件版本只信 `https://repo.maven.apache.org/maven2/.../maven-metadata.xml`。`search.maven.org` 的 solr 索引陈旧，会漏掉最新大版本。

### 编码约束

- 所有 schema 变更走 Flyway 迁移脚本，**禁用 `spring.jpa.hibernate.ddl-auto`**（保持 `validate`）。
- 每个模块的实现类一律 **package-private**，只有跨模块契约才是 `public`。
- 提交信息用中文，遵循 `type: 描述` 格式。

---

## File Structure

```
D:\MyMedia\
├── pom.xml
├── compose.yaml
├── .gitignore                                    （已存在）
├── docs/
│   ├── superpowers/{specs,plans}/                （已存在）
│   ├── adr/                                      本计划产出 ADR-001..003
│   └── walkthrough/                              本计划产出 01-infrastructure.md
└── src/
    ├── main/
    │   ├── java/com/mymedia/
    │   │   ├── MyMediaApplication.java           应用入口，Modulith 扫描根
    │   │   ├── shared/                           模块：基础类型与异常
    │   │   │   ├── package-info.java
    │   │   │   └── NotFoundException.java
    │   │   ├── user/                             模块：账号与认证
    │   │   │   ├── package-info.java
    │   │   │   ├── UserAccount.java              实体（public，跨模块只读引用 id）
    │   │   │   ├── UserRole.java                 枚举 ADMIN/USER
    │   │   │   ├── UserAccountRepository.java    package-private
    │   │   │   ├── UserRegistrationService.java  public API：注册
    │   │   │   ├── UserQueryService.java         public API：按名查询
    │   │   │   ├── SecurityConfig.java           package-private
    │   │   │   └── DatabaseUserDetailsService.java  package-private
    │   │   ├── library/                          模块：媒体库与访问控制
    │   │   │   ├── package-info.java
    │   │   │   ├── MediaLibrary.java             实体
    │   │   │   ├── LibraryDomain.java            枚举 VIDEO/IMAGE
    │   │   │   ├── MediaLibraryRepository.java   package-private
    │   │   │   ├── LibraryService.java           public API
    │   │   │   ├── LibraryAccessService.java     public API：权限判定
    │   │   │   └── LibraryController.java        package-private
    │   │   └── jobs/                             模块：任务队列
    │   │       ├── package-info.java
    │   │       ├── Job.java                      实体
    │   │       ├── JobStatus.java                枚举
    │   │       ├── JobRepository.java            package-private，含 SKIP LOCKED
    │   │       ├── JobQueue.java                 public API：入队
    │   │       ├── JobClaimService.java          package-private：抢占与租约
    │   │       ├── JobHandler.java               public SPI：由其他模块实现
    │   │       └── JobScheduler.java             package-private：轮询执行
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__extensions.sql
    │           ├── V2__users.sql
    │           ├── V3__libraries.sql
    │           └── V4__jobs.sql
    └── test/java/com/mymedia/
        ├── ModularityTests.java                  架构边界强制
        ├── AbstractIntegrationTest.java          Testcontainers 基类
        ├── user/UserRegistrationServiceTest.java
        ├── library/LibraryDomainConstraintTest.java
        ├── library/LibraryAccessServiceTest.java
        └── jobs/JobClaimServiceTest.java         并发抢占测试
```

---

## Task 1: 项目骨架与构建基线

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/mymedia/MyMediaApplication.java`
- Create: `src/main/resources/application.yml`

**Interfaces:**
- Consumes: 无（起始任务）
- Produces: `com.mymedia.MyMediaApplication` — Modulith 的扫描根类，后续所有架构测试以它为入口

- [ ] **Step 1: 创建 `pom.xml`**

以下内容为从 Spring Initializr 生成并**实测编译通过**的版本（已修正 parent 版本号）。原样使用：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.0</version>
		<relativePath/>
	</parent>
	<groupId>com.mymedia</groupId>
	<artifactId>mymedia</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>mymedia</name>
	<description>自托管媒体库服务端</description>
	<properties>
		<java.version>25</java.version>
		<spring-modulith.version>2.1.0</spring-modulith.version>
	</properties>
	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-starter-core</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-starter-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-observability-api</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-docker-compose</artifactId>
			<scope>runtime</scope>
			<optional>true</optional>
		</dependency>
		<dependency>
			<groupId>org.postgresql</groupId>
			<artifactId>postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-actuator</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-observability-core</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-runtime</artifactId>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>org.projectlombok</groupId>
			<artifactId>lombok</artifactId>
			<optional>true</optional>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-starter-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>
	<dependencyManagement>
		<dependencies>
			<dependency>
				<groupId>org.springframework.modulith</groupId>
				<artifactId>spring-modulith-bom</artifactId>
				<version>${spring-modulith.version}</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
		</dependencies>
	</dependencyManagement>
	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-compiler-plugin</artifactId>
				<executions>
					<execution>
						<id>default-compile</id>
						<phase>compile</phase>
						<goals><goal>compile</goal></goals>
						<configuration>
							<annotationProcessorPaths>
								<path>
									<groupId>org.projectlombok</groupId>
									<artifactId>lombok</artifactId>
								</path>
							</annotationProcessorPaths>
						</configuration>
					</execution>
					<execution>
						<id>default-testCompile</id>
						<phase>test-compile</phase>
						<goals><goal>testCompile</goal></goals>
						<configuration>
							<annotationProcessorPaths>
								<path>
									<groupId>org.projectlombok</groupId>
									<artifactId>lombok</artifactId>
								</path>
							</annotationProcessorPaths>
						</configuration>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
</project>
```

- [ ] **Step 2: 创建应用入口类**

`src/main/java/com/mymedia/MyMediaApplication.java`：

```java
package com.mymedia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyMediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMediaApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 `application.yml`**

`src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: mymedia
  threads:
    virtual:
      enabled: true          # 本项目是重阻塞 I/O 场景，见 spec §3.3
  datasource:
    url: jdbc:postgresql://localhost:5432/mymedia
    username: mymedia
    password: mymedia
  jpa:
    hibernate:
      ddl-auto: validate     # schema 由 Flyway 管理，绝不让 Hibernate 改表
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration

management:
  endpoints:
    web:
      exposure:
        include: health,info,modulith

logging:
  level:
    com.mymedia: DEBUG
```

- [ ] **Step 4: 验证构建**

```bash
cd /d/MyMedia && mvn -B -ntp test-compile > build.log 2>&1; echo "EXIT=$?"; grep -E "BUILD|ERROR" build.log | head -20
```

Expected: `EXIT=0` 且输出含 `BUILD SUCCESS`。

**若报 `Could not find artifact ...spring-boot-starter-parent:pom:4.1.0.RELEASE`**，说明 parent 版本号被误写成了 Initializr 内部 ID，改成 `4.1.0`。

- [ ] **Step 5: 提交**

```bash
cd /d/MyMedia
rm -f build.log
git add pom.xml src/main/java/com/mymedia/MyMediaApplication.java src/main/resources/application.yml
git commit -m "feat: 初始化 Spring Boot 4.1 项目骨架"
```

---

## Task 2: Docker Compose 与 pg_trgm 验证

本任务同时完成 spec §7.7 要求的 **P0 强制验收动作**：证实 `pg_trgm` 可用、且证实 PG 内置分词器确实不切分中文（后者反向支持了选型决策）。

**Files:**
- Create: `compose.yaml`

**Interfaces:**
- Consumes: Task 1 的 `application.yml`（datasource 指向 localhost:5432）
- Produces: 本地可用的 PostgreSQL 17 实例，库名 `mymedia`

- [ ] **Step 1: 创建 `compose.yaml`**

```yaml
services:
  postgres:
    image: 'postgres:17'
    container_name: mymedia-postgres
    environment:
      POSTGRES_DB: mymedia
      POSTGRES_USER: mymedia
      POSTGRES_PASSWORD: mymedia
    ports:
      - '5432:5432'
    volumes:
      - mymedia-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U mymedia -d mymedia']
      interval: 5s
      timeout: 5s
      retries: 10

volumes:
  mymedia-pgdata:
```

- [ ] **Step 2: 启动数据库**

```bash
cd /d/MyMedia && docker compose up -d
```

**前置条件**：Docker Desktop 必须已启动。若报 `failed to connect to the docker API`，先启动 Docker Desktop 再重试。

- [ ] **Step 3: 执行 pg_trgm 验收脚本**

```bash
docker exec mymedia-postgres psql -U mymedia -d mymedia -v ON_ERROR_STOP=1 \
  -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;" \
  -c "SELECT similarity('进击的巨人','巨人') AS trgm_similarity;" \
  -c "SELECT to_tsvector('simple','进击的巨人') AS chinese_tsvector;"
echo "EXIT=$?"
```

Expected：
- `CREATE EXTENSION` 成功
- `trgm_similarity` 返回一个**大于 0** 的数值 → 证实中文子串匹配可行
- `chinese_tsvector` 返回**单个未切分的 token** → 证实内置分词器不支持中文，反向确认了 spec §7.7 的选型

**若 `CREATE EXTENSION pg_trgm` 失败**：`pg_trgm` 不在该镜像中，必须改用自建 PostgreSQL 镜像方案，并**停下来回头修订 spec §7.7**，不要绕过。

- [ ] **Step 4: 提交**

```bash
cd /d/MyMedia
git add compose.yaml
git commit -m "feat: 添加 PostgreSQL 17 的 Docker Compose 配置

已验收 pg_trgm 扩展可用，中文 similarity 大于 0；
同时确认 to_tsvector 不切分中文，坐实 spec 7.7 的选型。"
```

---

## Task 3: Modulith 模块骨架与架构测试

**Files:**
- Create: `src/main/java/com/mymedia/shared/package-info.java`
- Create: `src/main/java/com/mymedia/shared/NotFoundException.java`
- Create: `src/main/java/com/mymedia/user/package-info.java`
- Create: `src/main/java/com/mymedia/library/package-info.java`
- Create: `src/main/java/com/mymedia/jobs/package-info.java`
- Test: `src/test/java/com/mymedia/ModularityTests.java`

**Interfaces:**
- Consumes: `com.mymedia.MyMediaApplication`（Task 1）
- Produces:
  - `com.mymedia.shared.NotFoundException extends RuntimeException` — 构造器 `NotFoundException(String message)`
  - 四个已声明的 Modulith 模块：`shared`、`user`、`library`、`jobs`
  - `ModularityTests` — 后续每个任务都必须保持它通过

- [ ] **Step 1: 先写会失败的架构测试**

`src/test/java/com/mymedia/ModularityTests.java`：

```java
package com.mymedia;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(MyMediaApplication.class);

    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }

    @Test
    void writesDocumentation() {
        new Documenter(MODULES).writeDocumentation();
    }
}
```

- [ ] **Step 2: 运行测试确认它失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ModularityTests -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|ERROR" t.log | head -10
```

Expected: 失败。此时还没有任何模块包，`ApplicationModules.of()` 找不到模块。

- [ ] **Step 3: 创建四个模块的 `package-info.java`**

`src/main/java/com/mymedia/shared/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(displayName = "Shared")
package com.mymedia.shared;
```

`src/main/java/com/mymedia/user/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(displayName = "User")
package com.mymedia.user;
```

`src/main/java/com/mymedia/library/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(displayName = "Library")
package com.mymedia.library;
```

`src/main/java/com/mymedia/jobs/package-info.java`：

```java
@org.springframework.modulith.ApplicationModule(displayName = "Jobs")
package com.mymedia.jobs;
```

- [ ] **Step 4: 创建 shared 模块的第一个类**

`src/main/java/com/mymedia/shared/NotFoundException.java`：

```java
package com.mymedia.shared;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ModularityTests -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log; ls target/spring-modulith-docs/
```

Expected: `EXIT=0`，`Tests run: 2, Failures: 0, Errors: 0`，且 `target/spring-modulith-docs/` 下生成了 `components.puml` 与各模块的 `.puml` / `.adoc`。

**这些 `.puml` 就是 README 要用的架构图。**

- [ ] **Step 6: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia/ModularityTests.java
git commit -m "feat: 声明 Modulith 模块边界并添加架构测试

shared/user/library/jobs 四个模块，边界由 ApplicationModules.verify()
强制。Documenter 会生成 components.puml 供 README 使用。"
```

---

## Task 4: Flyway 基线迁移与集成测试基类

**Files:**
- Create: `src/main/resources/db/migration/V1__extensions.sql`
- Test: `src/test/java/com/mymedia/AbstractIntegrationTest.java`
- Test: `src/test/java/com/mymedia/FlywayMigrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `application.yml`（flyway 配置）
- Produces: `com.mymedia.AbstractIntegrationTest` — 抽象基类，子类继承即获得一个真实 PostgreSQL 17 容器。**后续所有需要数据库的测试都继承它。**

- [ ] **Step 1: 写 Testcontainers 基类**

`src/test/java/com/mymedia/AbstractIntegrationTest.java`：

**注意包名**：Testcontainers 2.x 是 `org.testcontainers.postgresql.PostgreSQLContainer`，且**不带泛型参数**。

```java
package com.mymedia;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Import(AbstractIntegrationTest.ContainerConfig.class)
public abstract class AbstractIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ContainerConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
        }
    }
}
```

- [ ] **Step 2: 写会失败的迁移测试**

`src/test/java/com/mymedia/FlywayMigrationTest.java`：

```java
package com.mymedia;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pgTrgmExtensionIsInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void chineseSubstringSimilarityIsUsable() {
        Double similarity = jdbc.queryForObject(
                "SELECT similarity('进击的巨人', '巨人')", Double.class);
        assertThat(similarity).isGreaterThan(0.0);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=FlywayMigrationTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|expected" t.log | head -10
```

Expected: 失败，`pg_extension` 中查不到 `pg_trgm`（迁移脚本还没写）。

- [ ] **Step 4: 写基线迁移脚本**

`src/main/resources/db/migration/V1__extensions.sql`：

```sql
-- pg_trgm 提供三元组索引，是本项目中文搜索的主路径。
-- PostgreSQL 内置的 to_tsvector 不切分中文，无法满足需求，详见 spec 7.7。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

- [ ] **Step 5: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=FlywayMigrationTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V1__extensions.sql src/test/java/com/mymedia/AbstractIntegrationTest.java src/test/java/com/mymedia/FlywayMigrationTest.java
git commit -m "feat: 添加 Flyway 基线迁移与 Testcontainers 集成测试基类

V1 启用 pg_trgm。测试同时验证中文 similarity 可用。"
```

---

## Task 5: 用户实体与注册服务

**Files:**
- Create: `src/main/resources/db/migration/V2__users.sql`
- Create: `src/main/java/com/mymedia/user/UserRole.java`
- Create: `src/main/java/com/mymedia/user/UserAccount.java`
- Create: `src/main/java/com/mymedia/user/UserAccountRepository.java`
- Create: `src/main/java/com/mymedia/user/UserRegistrationService.java`
- Test: `src/test/java/com/mymedia/user/UserRegistrationServiceTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`（Task 4）
- Produces:
  - `public enum UserRole { ADMIN, USER }`
  - `public class UserAccount` — getter：`Long getId()`、`String getUsername()`、`String getPasswordHash()`、`UserRole getRole()`、`boolean isEnabled()`
  - `public class UserRegistrationService` — 方法 `public UserAccount register(String username, String rawPassword, UserRole role)`，用户名重复时抛 `IllegalArgumentException`

> **命名说明**：实体叫 `UserAccount` 而非 `User`，避免与 Spring Security 的 `org.springframework.security.core.userdetails.User` 混淆。表名用 `users`（`user` 在 PostgreSQL 中是保留字）。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/user/UserRegistrationServiceTest.java`：

```java
package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRegistrationServiceTest extends AbstractIntegrationTest {

    @Autowired
    UserRegistrationService registrationService;

    @Test
    void registersUserWithHashedPassword() {
        UserAccount account = registrationService.register("alice", "s3cret", UserRole.USER);

        assertThat(account.getId()).isNotNull();
        assertThat(account.getUsername()).isEqualTo("alice");
        assertThat(account.getRole()).isEqualTo(UserRole.USER);
        assertThat(account.isEnabled()).isTrue();
    }

    @Test
    void neverStoresRawPassword() {
        UserAccount account = registrationService.register("bob", "s3cret", UserRole.USER);

        assertThat(account.getPasswordHash()).doesNotContain("s3cret");
        assertThat(account.getPasswordHash()).startsWith("{bcrypt}");
    }

    @Test
    void rejectsDuplicateUsername() {
        registrationService.register("carol", "pw1", UserRole.USER);

        assertThatThrownBy(() -> registrationService.register("carol", "pw2", UserRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carol");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=UserRegistrationServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`UserRegistrationService` 等类不存在。

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V2__users.sql`：

```sql
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(128),
    role          VARCHAR(16)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'USER'))
);
```

- [ ] **Step 4: 写枚举与实体**

`src/main/java/com/mymedia/user/UserRole.java`：

```java
package com.mymedia.user;

public enum UserRole { ADMIN, USER }
```

`src/main/java/com/mymedia/user/UserAccount.java`：

```java
package com.mymedia.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "display_name", length = 128)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UserAccount() {
        // JPA 要求的无参构造器
    }

    UserAccount(String username, String passwordHash, UserRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.displayName = username;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public UserRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: 写仓储与注册服务**

`src/main/java/com/mymedia/user/UserAccountRepository.java`：

```java
package com.mymedia.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsername(String username);

    boolean existsByUsername(String username);
}
```

`src/main/java/com/mymedia/user/UserRegistrationService.java`：

```java
package com.mymedia.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserRegistrationService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    UserRegistrationService(UserAccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserAccount register(String username, String rawPassword, UserRole role) {
        if (repository.existsByUsername(username)) {
            throw new IllegalArgumentException("用户名已被占用: " + username);
        }
        String hash = passwordEncoder.encode(rawPassword);
        return repository.save(new UserAccount(username, hash, role));
    }
}
```

- [ ] **Step 6: 提供 PasswordEncoder bean**

`src/main/java/com/mymedia/user/SecurityConfig.java`（本任务先只放 encoder，Task 7 再补过滤器链）：

```java
package com.mymedia.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder 会给哈希加 {bcrypt} 前缀，
        // 使将来更换算法时旧密码仍可校验。
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=UserRegistrationServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 8: 确认架构测试仍然通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ModularityTests -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 2, Failures: 0`

- [ ] **Step 9: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V2__users.sql src/main/java/com/mymedia/user src/test/java/com/mymedia/user
git commit -m "feat: 添加用户实体与注册服务

密码经 DelegatingPasswordEncoder 哈希，带 {bcrypt} 前缀以便日后换算法。
实体命名 UserAccount 避免与 Spring Security 的 User 冲突；
表名 users 因为 user 是 PostgreSQL 保留字。"
```

---

## Task 6: 认证配置与登录

**Files:**
- Create: `src/main/java/com/mymedia/user/DatabaseUserDetailsService.java`
- Create: `src/main/java/com/mymedia/user/UserQueryService.java`
- Modify: `src/main/java/com/mymedia/user/SecurityConfig.java`
- Test: `src/test/java/com/mymedia/user/AuthenticationTest.java`

**Interfaces:**
- Consumes: `UserAccountRepository`、`UserRegistrationService`（Task 5）
- Produces:
  - `public class UserQueryService` — `public Optional<UserAccount> findByUsername(String username)`、`public UserAccount getById(Long id)`（找不到抛 `NotFoundException`）
  - 受保护的 HTTP 端点：除 `/api/auth/**` 与 `/actuator/health` 外一律需要认证

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/user/AuthenticationTest.java`：

```java
package com.mymedia.user;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

@AutoConfigureMockMvc
class AuthenticationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    @BeforeEach
    void setUp() {
        if (registrationService != null) {
            try {
                registrationService.register("dave", "pw123", UserRole.USER);
            } catch (IllegalArgumentException ignored) {
                // 已注册过，忽略
            }
        }
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/libraries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointAcceptsValidCredentials() throws Exception {
        mockMvc.perform(get("/api/libraries").with(httpBasic("dave", "pw123")))
                .andExpect(status().isNotFound());   // 端点尚未实现，但认证已通过
    }

    @Test
    void protectedEndpointRejectsWrongPassword() throws Exception {
        mockMvc.perform(get("/api/libraries").with(httpBasic("dave", "wrong")))
                .andExpect(status().isUnauthorized());
    }
}
```

> **说明**：第三个测试期望 `404` 而非 `200`——`/api/libraries` 要到 Task 10 才实现。这里验证的是"认证通过了，请求进到了路由层"，`401` 与 `404` 的区别正是认证是否成功。

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=AuthenticationTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|expected" t.log | head -10
```

Expected: 失败。没有 `SecurityFilterChain`，Boot 的默认安全配置会用随机生成的密码，`dave` 无法登录。

- [ ] **Step 3: 写 UserDetailsService**

`src/main/java/com/mymedia/user/DatabaseUserDetailsService.java`：

```java
package com.mymedia.user;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountRepository repository;

    DatabaseUserDetailsService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("找不到用户: " + username));

        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .roles(account.getRole().name())
                .disabled(!account.isEnabled())
                .build();
    }
}
```

- [ ] **Step 4: 写查询服务（供其他模块使用的公开 API）**

`src/main/java/com/mymedia/user/UserQueryService.java`：

```java
package com.mymedia.user;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserQueryService {

    private final UserAccountRepository repository;

    UserQueryService(UserAccountRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findByUsername(String username) {
        return repository.findByUsername(username);
    }

    @Transactional(readOnly = true)
    public UserAccount getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到用户 id=" + id));
    }
}
```

- [ ] **Step 5: 补全 SecurityConfig 的过滤器链**

替换 `src/main/java/com/mymedia/user/SecurityConfig.java` 的全部内容：

```java
package com.mymedia.user;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                // 本服务是纯 REST API，用 HTTP Basic + 无状态会话，不需要 CSRF 令牌。
                // 决策记录见 docs/adr/ADR-002-认证方案.md
                .csrf(csrf -> csrf.disable())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder 会给哈希加 {bcrypt} 前缀，
        // 使将来更换算法时旧密码仍可校验。
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=AuthenticationTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: 写 ADR**

`docs/adr/ADR-002-认证方案.md`：

```markdown
# ADR-002：认证采用 HTTP Basic + 无状态

## 状态
已接受（2026-08-17）

## 背景
服务是纯 REST API，前端为独立的 Vue SPA，需要一种认证机制。

## 决策
使用 HTTP Basic 认证，禁用 CSRF 保护。

## 理由
- 本服务不使用 Cookie 会话，浏览器不会自动携带凭证，因此不存在 CSRF 攻击面。CSRF 令牌在无状态 API 上是纯粹的复杂度。
- 相比 JWT，Basic 认证少了令牌签发、过期、刷新、吊销四套机制。本项目是单实例部署，没有跨服务传递身份的需求，JWT 的核心优势用不上。
- 密码经 `DelegatingPasswordEncoder` 以 bcrypt 存储，哈希带 `{bcrypt}` 前缀，将来更换算法时旧密码仍可校验。

## 后果
- 每个请求都要做一次 bcrypt 校验，有 CPU 开销。当前数据量下可接受；若成为瓶颈，可在 Basic 之上加一层短期令牌缓存。
- 必须在 HTTPS 下部署，否则凭证明文传输。本项目交付形态是本地 Docker Compose，不涉及公网。

## 备选方案
- **JWT**：为无跨服务需求的单实例引入令牌生命周期管理，收益不抵复杂度。
- **Session Cookie**：需要 CSRF 防护与会话存储，对 SPA + REST 的组合是额外负担。
```

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/user src/test/java/com/mymedia/user docs/adr
git commit -m "feat: 添加数据库认证与安全过滤器链

HTTP Basic + 无状态，禁用 CSRF（无 Cookie 会话即无 CSRF 面）。
决策记录见 ADR-002。"
```

---

## Task 7: 媒体库与域分区的数据库级强制

本任务实现 spec §5.1 的核心约束。**这是整个域分区设计的地基**：视频条目在数据库层面就不可能落进图片库。

**Files:**
- Create: `src/main/resources/db/migration/V3__libraries.sql`
- Create: `src/main/java/com/mymedia/library/LibraryDomain.java`
- Create: `src/main/java/com/mymedia/library/MediaLibrary.java`
- Create: `src/main/java/com/mymedia/library/MediaLibraryRepository.java`
- Create: `src/main/java/com/mymedia/library/LibraryService.java`
- Test: `src/test/java/com/mymedia/library/LibraryDomainConstraintTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`（Task 4）、`NotFoundException`（Task 3）
- Produces:
  - `public enum LibraryDomain { VIDEO, IMAGE }`
  - `public class MediaLibrary` — getter：`Long getId()`、`String getName()`、`LibraryDomain getDomain()`、`String getRootPath()`、`boolean isEnabled()`
  - `public class LibraryService` — `public MediaLibrary create(String name, LibraryDomain domain, String rootPath)`、`public MediaLibrary getById(Long id)`、`public List<MediaLibrary> findAll()`

- [ ] **Step 1: 写会失败的约束测试**

`src/test/java/com/mymedia/library/LibraryDomainConstraintTest.java`：

```java
package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryDomainConstraintTest extends AbstractIntegrationTest {

    @Autowired
    LibraryService libraryService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createsVideoLibrary() {
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, "/media/movies");

        assertThat(library.getId()).isNotNull();
        assertThat(library.getDomain()).isEqualTo(LibraryDomain.VIDEO);
    }

    @Test
    void domainIsExposedAsCompositeUniqueKeyForForeignKeyReference() {
        // libraries 上必须有 (id, domain) 唯一键，否则子表无法用复合外键引用它。
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'uq_library_domain' AND contype = 'u'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsUnknownDomainValue() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO libraries (name, domain, root_path, enabled, created_at)
                VALUES ('坏库', 'AUDIO', '/media/audio', true, now())
                """))
                .hasMessageContaining("ck_libraries_domain");
    }

    @Test
    void rejectsDuplicateRootPath() {
        libraryService.create("图集", LibraryDomain.IMAGE, "/media/gallery");

        assertThatThrownBy(() ->
                libraryService.create("图集副本", LibraryDomain.IMAGE, "/media/gallery"))
                .isInstanceOf(Exception.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=LibraryDomainConstraintTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`LibraryService` 等类不存在。

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V3__libraries.sql`：

```sql
CREATE TABLE libraries (
    id                 BIGSERIAL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    domain             VARCHAR(8)   NOT NULL,
    root_path          TEXT         NOT NULL UNIQUE,
    scan_cron          VARCHAR(64),
    metadata_providers TEXT[]       NOT NULL DEFAULT '{}',
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_libraries_domain CHECK (domain IN ('VIDEO', 'IMAGE'))
);

-- 关键：这个看似冗余的唯一键，是让子表能用复合外键把自己的 domain
-- 钉死在所属库的 domain 上的前提。CHECK 约束无法跨表引用，
-- 复合外键是 PostgreSQL 中声明式强制跨表不变式的标准手法。
-- 效果：视频条目在数据库层面就不可能落进图片库。详见 spec 5.1。
ALTER TABLE libraries ADD CONSTRAINT uq_library_domain UNIQUE (id, domain);

CREATE TABLE library_access (
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    library_id BIGINT NOT NULL REFERENCES libraries (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, library_id)
);

CREATE INDEX idx_library_access_user ON library_access (user_id);
```

- [ ] **Step 4: 写枚举与实体**

`src/main/java/com/mymedia/library/LibraryDomain.java`：

```java
package com.mymedia.library;

/**
 * 媒体库的顶层分区。创建后不可变——改变一个库的 domain 等同于
 * 把它的全部内容换成另一种形态，语义上应该是新建一个库。
 */
public enum LibraryDomain { VIDEO, IMAGE }
```

`src/main/java/com/mymedia/library/MediaLibrary.java`：

```java
package com.mymedia.library;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "libraries")
public class MediaLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8, updatable = false)
    private LibraryDomain domain;

    @Column(name = "root_path", nullable = false, unique = true)
    private String rootPath;

    @Column(name = "scan_cron", length = 64)
    private String scanCron;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected MediaLibrary() {
        // JPA 要求的无参构造器
    }

    MediaLibrary(String name, LibraryDomain domain, String rootPath) {
        this.name = name;
        this.domain = domain;
        this.rootPath = rootPath;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public LibraryDomain getDomain() { return domain; }
    public String getRootPath() { return rootPath; }
    public String getScanCron() { return scanCron; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    void rename(String newName) {
        this.name = newName;
    }
}
```

> **注意** `domain` 上的 `updatable = false`：它是不可变字段，JPA 层面也不允许更新。

- [ ] **Step 5: 写仓储与服务**

`src/main/java/com/mymedia/library/MediaLibraryRepository.java`：

```java
package com.mymedia.library;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface MediaLibraryRepository extends JpaRepository<MediaLibrary, Long> {

    List<MediaLibrary> findByDomain(LibraryDomain domain);
}
```

`src/main/java/com/mymedia/library/LibraryService.java`：

```java
package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LibraryService {

    private final MediaLibraryRepository repository;

    LibraryService(MediaLibraryRepository repository) {
        this.repository = repository;
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
}
```

> **`saveAndFlush` 而非 `save`**：唯一约束冲突必须在方法内立即抛出，而不是等到事务提交时才炸。Task 7 Step 1 的 `rejectsDuplicateRootPath` 测试依赖这一点。

- [ ] **Step 6: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=LibraryDomainConstraintTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 7: 写 ADR**

`docs/adr/ADR-001-域分区的数据库级强制.md`：

```markdown
# ADR-001：用复合外键在数据库层面强制域分区

## 状态
已接受（2026-08-17）

## 背景
系统按视频域与图片域做顶层分区。需要保证「视频条目不会落进图片库」这个不变式。

## 决策
在 `libraries` 上建立冗余唯一键 `UNIQUE (id, domain)`，各领域表冗余 `domain` 列，
并用复合外键 `FOREIGN KEY (library_id, domain) REFERENCES libraries (id, domain)`
配合 `CHECK (domain = 'VIDEO')` 把它钉死。

## 理由
- CHECK 约束**无法跨表引用**，所以「条目的 domain 必须等于其所属库的 domain」
  这个不变式无法用单表 CHECK 表达。
- 只靠应用层校验，意味着任何一处忘了校验、或任何一次直连数据库的手工订正，
  都能写进脏数据。分区是整个架构的地基，地基不能靠自觉。
- 复合外键是 PostgreSQL 中声明式强制跨表不变式的标准手法，代价仅为一列冗余。

## 后果
- 每个领域表多一列 `domain`，且必须与 `library_id` 同时写入。
- 迁移脚本必须先建唯一键再建外键，顺序不可颠倒。
- 换取的是：脏数据在数据库层面不可能产生。

## 备选方案
- **应用层校验**：单点遗漏即失效，无法防御直连数据库的写入。
- **触发器**：能实现，但比声明式约束难读、难调试，且性能更差。
```

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V3__libraries.sql src/main/java/com/mymedia/library src/test/java/com/mymedia/library docs/adr
git commit -m "feat: 添加媒体库与域分区的数据库级强制

libraries 上的 UNIQUE (id, domain) 是复合外键的锚点，
使子表能把自己的 domain 钉死在所属库上。决策记录见 ADR-001。"
```

---

## Task 8: 媒体库访问控制

**Files:**
- Create: `src/main/java/com/mymedia/library/LibraryAccessService.java`
- Test: `src/test/java/com/mymedia/library/LibraryAccessServiceTest.java`

**Interfaces:**
- Consumes: `LibraryService`、`MediaLibrary`（Task 7）、`UserRegistrationService`、`UserAccount`、`UserRole`（Task 5）
- Produces: `public class LibraryAccessService`
  - `public boolean canAccess(Long userId, Long libraryId)`
  - `public void grant(Long userId, Long libraryId)`
  - `public void revoke(Long userId, Long libraryId)`
  - `public List<MediaLibrary> accessibleLibraries(Long userId)`

**关键规则**：ADMIN 隐式拥有全部媒体库的访问权，无需 `library_access` 记录。

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/library/LibraryAccessServiceTest.java`：

```java
package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryAccessServiceTest extends AbstractIntegrationTest {

    @Autowired
    LibraryAccessService accessService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    UserRegistrationService registrationService;

    private String uniqueName() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniquePath() {
        return "/media/" + UUID.randomUUID();
    }

    @Test
    void regularUserHasNoAccessByDefault() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        assertThat(accessService.canAccess(user.getId(), library.getId())).isFalse();
    }

    @Test
    void grantedUserHasAccess() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        accessService.grant(user.getId(), library.getId());

        assertThat(accessService.canAccess(user.getId(), library.getId())).isTrue();
    }

    @Test
    void revokedUserLosesAccess() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        accessService.grant(user.getId(), library.getId());
        accessService.revoke(user.getId(), library.getId());

        assertThat(accessService.canAccess(user.getId(), library.getId())).isFalse();
    }

    @Test
    void adminHasImplicitAccessToEveryLibrary() {
        UserAccount admin = registrationService.register(uniqueName(), "pw", UserRole.ADMIN);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        // 从未 grant 过，但 ADMIN 隐式拥有全部访问权
        assertThat(accessService.canAccess(admin.getId(), library.getId())).isTrue();
    }

    @Test
    void accessibleLibrariesListsOnlyGrantedOnes() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary granted = libraryService.create("已授权", LibraryDomain.VIDEO, uniquePath());
        libraryService.create("未授权", LibraryDomain.IMAGE, uniquePath());

        accessService.grant(user.getId(), granted.getId());

        assertThat(accessService.accessibleLibraries(user.getId()))
                .extracting(MediaLibrary::getId)
                .containsExactly(granted.getId());
    }

    @Test
    void grantIsIdempotent() {
        UserAccount user = registrationService.register(uniqueName(), "pw", UserRole.USER);
        MediaLibrary library = libraryService.create("电影", LibraryDomain.VIDEO, uniquePath());

        accessService.grant(user.getId(), library.getId());
        accessService.grant(user.getId(), library.getId());   // 重复授权不应报错

        assertThat(accessService.canAccess(user.getId(), library.getId())).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=LibraryAccessServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`LibraryAccessService` 不存在。

- [ ] **Step 3: 实现访问控制服务**

`src/main/java/com/mymedia/library/LibraryAccessService.java`：

```java
package com.mymedia.library;

import com.mymedia.user.UserAccount;
import com.mymedia.user.UserQueryService;
import com.mymedia.user.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LibraryAccessService {

    private final JdbcTemplate jdbc;
    private final UserQueryService userQueryService;
    private final MediaLibraryRepository libraryRepository;

    LibraryAccessService(JdbcTemplate jdbc,
                         UserQueryService userQueryService,
                         MediaLibraryRepository libraryRepository) {
        this.jdbc = jdbc;
        this.userQueryService = userQueryService;
        this.libraryRepository = libraryRepository;
    }

    @Transactional(readOnly = true)
    public boolean canAccess(Long userId, Long libraryId) {
        if (isAdmin(userId)) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM library_access WHERE user_id = ? AND library_id = ?",
                Integer.class, userId, libraryId);
        return count != null && count > 0;
    }

    @Transactional
    public void grant(Long userId, Long libraryId) {
        // ON CONFLICT DO NOTHING 使重复授权成为幂等操作
        jdbc.update("""
                INSERT INTO library_access (user_id, library_id) VALUES (?, ?)
                ON CONFLICT (user_id, library_id) DO NOTHING
                """, userId, libraryId);
    }

    @Transactional
    public void revoke(Long userId, Long libraryId) {
        jdbc.update("DELETE FROM library_access WHERE user_id = ? AND library_id = ?",
                userId, libraryId);
    }

    @Transactional(readOnly = true)
    public List<MediaLibrary> accessibleLibraries(Long userId) {
        if (isAdmin(userId)) {
            return libraryRepository.findAll();
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT library_id FROM library_access WHERE user_id = ?", Long.class, userId);
        return ids.isEmpty() ? List.of() : libraryRepository.findAllById(ids);
    }

    private boolean isAdmin(Long userId) {
        UserAccount account = userQueryService.getById(userId);
        return account.getRole() == UserRole.ADMIN;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=LibraryAccessServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: 确认架构测试仍然通过**

`library` 模块现在依赖了 `user` 模块的公开 API（`UserQueryService`、`UserAccount`、`UserRole`），这是允许的同步依赖。

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=ModularityTests -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 2, Failures: 0`

**若失败并提示模块越界**，说明引用了 `user` 模块的 package-private 类型。检查是否误用了 `UserAccountRepository`（它是 package-private，只能在 `user` 模块内使用）。

- [ ] **Step 6: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/library src/test/java/com/mymedia/library
git commit -m "feat: 添加媒体库访问控制

ADMIN 隐式拥有全部访问权，无需 library_access 记录。
grant 用 ON CONFLICT DO NOTHING 实现幂等。"
```

---

## Task 9: 媒体库 REST API

**Files:**
- Create: `src/main/java/com/mymedia/library/LibraryController.java`
- Create: `src/main/java/com/mymedia/library/LibraryDto.java`
- Create: `src/main/java/com/mymedia/shared/GlobalExceptionHandler.java`
- Test: `src/test/java/com/mymedia/library/LibraryControllerTest.java`

**Interfaces:**
- Consumes: `LibraryService`、`LibraryAccessService`（Task 7、8）、`UserQueryService`（Task 6）
- Produces: HTTP 端点
  - `GET /api/libraries` → 当前用户可访问的媒体库列表
  - `POST /api/libraries` → 创建媒体库（仅 ADMIN）
  - `GET /api/libraries/{id}` → 单个媒体库（需访问权，否则 404）

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/library/LibraryControllerTest.java`：

```java
package com.mymedia.library;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserRegistrationService;
import com.mymedia.user.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LibraryControllerTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRegistrationService registrationService;

    @Autowired
    LibraryService libraryService;

    @Autowired
    LibraryAccessService accessService;

    private String uniqueName() {
        return "u" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniquePath() {
        return "/media/" + UUID.randomUUID();
    }

    @Test
    void adminCanCreateLibrary() throws Exception {
        String admin = uniqueName();
        registrationService.register(admin, "pw", UserRole.ADMIN);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"电影","domain":"VIDEO","rootPath":"%s"}
                                """.formatted(uniquePath())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("电影"))
                .andExpect(jsonPath("$.domain").value("VIDEO"));
    }

    @Test
    void regularUserCannotCreateLibrary() throws Exception {
        String user = uniqueName();
        registrationService.register(user, "pw", UserRole.USER);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(user, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"电影","domain":"VIDEO","rootPath":"%s"}
                                """.formatted(uniquePath())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listReturnsOnlyAccessibleLibraries() throws Exception {
        String user = uniqueName();
        UserAccount account = registrationService.register(user, "pw", UserRole.USER);
        MediaLibrary granted = libraryService.create("已授权", LibraryDomain.VIDEO, uniquePath());
        libraryService.create("未授权", LibraryDomain.IMAGE, uniquePath());
        accessService.grant(account.getId(), granted.getId());

        mockMvc.perform(get("/api/libraries").with(httpBasic(user, "pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("已授权"));
    }

    @Test
    void inaccessibleLibraryReturnsNotFound() throws Exception {
        String user = uniqueName();
        registrationService.register(user, "pw", UserRole.USER);
        MediaLibrary hidden = libraryService.create("看不见", LibraryDomain.VIDEO, uniquePath());

        // 返回 404 而非 403：不向无权访问者泄露资源是否存在
        mockMvc.perform(get("/api/libraries/" + hidden.getId()).with(httpBasic(user, "pw")))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidDomain() throws Exception {
        String admin = uniqueName();
        registrationService.register(admin, "pw", UserRole.ADMIN);

        mockMvc.perform(post("/api/libraries")
                        .with(httpBasic(admin, "pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"音乐","domain":"AUDIO","rootPath":"%s"}
                                """.formatted(uniquePath())))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=LibraryControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run|Status" t.log | head -10
```

Expected: 失败，端点返回 404（控制器还不存在）。

- [ ] **Step 3: 写 DTO**

`src/main/java/com/mymedia/library/LibraryDto.java`：

```java
package com.mymedia.library;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class LibraryDto {

    private LibraryDto() {
    }

    public record CreateRequest(
            @NotBlank String name,
            @NotNull LibraryDomain domain,
            @NotBlank String rootPath) {
    }

    public record Response(
            Long id,
            String name,
            LibraryDomain domain,
            String rootPath,
            boolean enabled) {

        static Response from(MediaLibrary library) {
            return new Response(
                    library.getId(),
                    library.getName(),
                    library.getDomain(),
                    library.getRootPath(),
                    library.isEnabled());
        }
    }
}
```

- [ ] **Step 4: 写控制器**

`src/main/java/com/mymedia/library/LibraryController.java`：

```java
package com.mymedia.library;

import com.mymedia.shared.NotFoundException;
import com.mymedia.user.UserAccount;
import com.mymedia.user.UserQueryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/libraries")
class LibraryController {

    private final LibraryService libraryService;
    private final LibraryAccessService accessService;
    private final UserQueryService userQueryService;

    LibraryController(LibraryService libraryService,
                      LibraryAccessService accessService,
                      UserQueryService userQueryService) {
        this.libraryService = libraryService;
        this.accessService = accessService;
        this.userQueryService = userQueryService;
    }

    @GetMapping
    List<LibraryDto.Response> list(@AuthenticationPrincipal UserDetails principal) {
        Long userId = currentUserId(principal);
        return accessService.accessibleLibraries(userId).stream()
                .map(LibraryDto.Response::from)
                .toList();
    }

    @GetMapping("/{id}")
    LibraryDto.Response getOne(@AuthenticationPrincipal UserDetails principal,
                               @PathVariable Long id) {
        Long userId = currentUserId(principal);
        if (!accessService.canAccess(userId, id)) {
            // 返回 404 而非 403：不向无权访问者泄露资源是否存在
            throw new NotFoundException("找不到媒体库 id=" + id);
        }
        return LibraryDto.Response.from(libraryService.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    LibraryDto.Response create(@Valid @RequestBody LibraryDto.CreateRequest request) {
        MediaLibrary library = libraryService.create(
                request.name(), request.domain(), request.rootPath());
        return LibraryDto.Response.from(library);
    }

    private Long currentUserId(UserDetails principal) {
        UserAccount account = userQueryService.findByUsername(principal.getUsername())
                .orElseThrow(() -> new NotFoundException("找不到用户: " + principal.getUsername()));
        return account.getId();
    }
}
```

- [ ] **Step 5: 启用方法级安全**

在 `src/main/java/com/mymedia/user/SecurityConfig.java` 的类注解上追加 `@EnableMethodSecurity`：

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {
```

并补上 import：

```java
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
```

> `@PreAuthorize` 没有 `@EnableMethodSecurity` 就是一个**静默失效的注解**——不报错，但完全不生效。`regularUserCannotCreateLibrary` 这个测试正是为了抓住这种失效。

- [ ] **Step 6: 写全局异常处理**

`src/main/java/com/mymedia/shared/GlobalExceptionHandler.java`：

```java
package com.mymedia.shared;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=LibraryControllerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia src/test/java/com/mymedia/library/LibraryControllerTest.java
git commit -m "feat: 添加媒体库 REST API

无权访问的媒体库返回 404 而非 403，避免泄露资源存在性。
@PreAuthorize 需要 @EnableMethodSecurity 才生效，已加测试守护。"
```

---

## Task 10: 任务表与入队 API

**Files:**
- Create: `src/main/resources/db/migration/V4__jobs.sql`
- Create: `src/main/java/com/mymedia/jobs/JobStatus.java`
- Create: `src/main/java/com/mymedia/jobs/Job.java`
- Create: `src/main/java/com/mymedia/jobs/JobRepository.java`
- Create: `src/main/java/com/mymedia/jobs/JobQueue.java`
- Test: `src/test/java/com/mymedia/jobs/JobQueueTest.java`

**Interfaces:**
- Consumes: `AbstractIntegrationTest`（Task 4）
- Produces:
  - `public enum JobStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }`
  - `public class Job` — getter：`Long getId()`、`String getType()`、`String getPayload()`、`JobStatus getStatus()`、`int getAttempts()`、`String getLastError()`、`Instant getScheduledAt()`、`String getLeaseOwner()`、`Instant getLeaseExpiresAt()`
  - `public class JobQueue` — `public Long enqueue(String type, String payloadJson, String dedupKey)`，`dedupKey` 已存在未完成任务时返回既有任务 id

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/jobs/JobQueueTest.java`：

```java
package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobQueueTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue jobQueue;

    private String uniqueKey() {
        return "k" + UUID.randomUUID();
    }

    @Test
    void enqueuesJobInPendingState() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{\"libraryId\":1}", uniqueKey());

        Job job = jobQueue.findById(id);
        assertThat(job.getType()).isEqualTo("LIBRARY_SCAN");
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getPayload()).contains("libraryId");
    }

    @Test
    void deduplicatesByKey() {
        String key = uniqueKey();

        Long first = jobQueue.enqueue("LIBRARY_SCAN", "{\"libraryId\":1}", key);
        Long second = jobQueue.enqueue("LIBRARY_SCAN", "{\"libraryId\":1}", key);

        // 同一个 dedupKey 不应产生第二个任务——防止同一个库被反复排入扫描
        assertThat(second).isEqualTo(first);
    }

    @Test
    void allowsSameKeyAfterPreviousCompleted() {
        String key = uniqueKey();
        Long first = jobQueue.enqueue("LIBRARY_SCAN", "{}", key);
        jobQueue.markSucceeded(first);

        Long second = jobQueue.enqueue("LIBRARY_SCAN", "{}", key);

        // 上一次已完成，同一个 key 应能再次入队
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void nullDedupKeyNeverDeduplicates() {
        Long first = jobQueue.enqueue("PREVIEW_GENERATE", "{\"fileId\":1}", null);
        Long second = jobQueue.enqueue("PREVIEW_GENERATE", "{\"fileId\":1}", null);

        assertThat(second).isNotEqualTo(first);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=JobQueueTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`JobQueue` 不存在。

- [ ] **Step 3: 写迁移脚本**

`src/main/resources/db/migration/V4__jobs.sql`：

```sql
CREATE TABLE job (
    id               BIGSERIAL PRIMARY KEY,
    type             VARCHAR(48)  NOT NULL,
    payload          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    priority         INT          NOT NULL DEFAULT 0,
    attempts         INT          NOT NULL DEFAULT 0,
    max_attempts     INT          NOT NULL DEFAULT 3,
    last_error       TEXT,
    dedup_key        VARCHAR(128),
    scheduled_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    lease_owner      VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_job_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

-- 去重只对「未完成」的任务生效：同一个库不应被同时排入两次扫描，
-- 但上一次扫描完成后必须能再次排入。部分唯一索引正好表达这个语义。
CREATE UNIQUE INDEX uq_job_dedup_active
    ON job (dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('PENDING', 'RUNNING');

-- 抢占查询的支撑索引：status + scheduled_at 是 WHERE 与 ORDER BY 的组合
CREATE INDEX idx_job_claim ON job (status, scheduled_at) WHERE status = 'PENDING';

-- 租约回收查询的支撑索引
CREATE INDEX idx_job_lease ON job (lease_expires_at) WHERE status = 'RUNNING';
```

- [ ] **Step 4: 写枚举与实体**

`src/main/java/com/mymedia/jobs/JobStatus.java`：

```java
package com.mymedia.jobs;

public enum JobStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }
```

`src/main/java/com/mymedia/jobs/Job.java`：

```java
package com.mymedia.jobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "job")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 48)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload = "{}";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status = JobStatus.PENDING;

    @Column(nullable = false)
    private int priority = 0;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "dedup_key", length = 128)
    private String dedupKey;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt = Instant.now();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Job() {
        // JPA 要求的无参构造器
    }

    Job(String type, String payload, String dedupKey) {
        this.type = type;
        this.payload = payload;
        this.dedupKey = dedupKey;
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public JobStatus getStatus() { return status; }
    public int getPriority() { return priority; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getLastError() { return lastError; }
    public String getDedupKey() { return dedupKey; }
    public Instant getScheduledAt() { return scheduledAt; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }

    void markRunning(String owner, Instant leaseExpiry) {
        this.status = JobStatus.RUNNING;
        this.leaseOwner = owner;
        this.leaseExpiresAt = leaseExpiry;
        this.startedAt = Instant.now();
        this.attempts = this.attempts + 1;
    }

    void markSucceeded() {
        this.status = JobStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    /**
     * 失败后决定重试还是放弃。未达最大尝试次数则退回 PENDING 并按指数退避
     * 推迟下次调度时间；否则终结为 FAILED 并保留错误信息供排查。
     */
    void markFailed(String error, Instant nextAttemptAt) {
        this.lastError = error;
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
        if (this.attempts >= this.maxAttempts) {
            this.status = JobStatus.FAILED;
            this.finishedAt = Instant.now();
        } else {
            this.status = JobStatus.PENDING;
            this.scheduledAt = nextAttemptAt;
        }
    }
}
```

- [ ] **Step 5: 写仓储**

`src/main/java/com/mymedia/jobs/JobRepository.java`：

```java
package com.mymedia.jobs;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface JobRepository extends JpaRepository<Job, Long> {

    @Query("""
            SELECT j FROM Job j
            WHERE j.dedupKey = :dedupKey AND j.status IN (
                com.mymedia.jobs.JobStatus.PENDING, com.mymedia.jobs.JobStatus.RUNNING)
            """)
    Optional<Job> findActiveByDedupKey(@Param("dedupKey") String dedupKey);

    /**
     * FOR UPDATE SKIP LOCKED 是 PostgreSQL 的行级抢占原语：
     * 多个 worker 并发执行这条查询时，各自跳过已被他人锁住的行，
     * 因而拿到互不相交的任务集合，且互不阻塞。
     * 这是不引入消息队列却能安全并发消费的关键，见 ADR-003。
     */
    @Query(value = """
            SELECT * FROM job
            WHERE status = 'PENDING' AND scheduled_at <= :now
            ORDER BY priority DESC, scheduled_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> claimBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * 回收租约已过期的任务：worker 进程崩溃后，它持有的任务会永远停在
     * RUNNING。租约到期即视为 worker 已死，任务退回 PENDING 供他人重新抢占。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE job
            SET status = 'PENDING', lease_owner = NULL, lease_expires_at = NULL
            WHERE status = 'RUNNING' AND lease_expires_at < :now
            """, nativeQuery = true)
    int reclaimExpiredLeases(@Param("now") Instant now);
}
```

- [ ] **Step 6: 写入队 API**

`src/main/java/com/mymedia/jobs/JobQueue.java`：

```java
package com.mymedia.jobs;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobQueue {

    private final JobRepository repository;

    JobQueue(JobRepository repository) {
        this.repository = repository;
    }

    /**
     * 入队一个任务。若 dedupKey 非空且已存在同 key 的未完成任务，
     * 直接返回既有任务的 id，不新建。
     */
    @Transactional
    public Long enqueue(String type, String payloadJson, String dedupKey) {
        if (dedupKey != null) {
            var existing = repository.findActiveByDedupKey(dedupKey);
            if (existing.isPresent()) {
                return existing.get().getId();
            }
        }
        String payload = payloadJson == null ? "{}" : payloadJson;
        return repository.saveAndFlush(new Job(type, payload, dedupKey)).getId();
    }

    @Transactional(readOnly = true)
    public Job findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + id));
    }

    @Transactional
    public void markSucceeded(Long id) {
        Job job = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + id));
        job.markSucceeded();
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=JobQueueTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 8: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/resources/db/migration/V4__jobs.sql src/main/java/com/mymedia/jobs src/test/java/com/mymedia/jobs
git commit -m "feat: 添加任务表与入队 API

去重用部分唯一索引，只约束 PENDING/RUNNING 状态——
同一个库不能被同时排入两次扫描，但上次完成后可再次排入。"
```

---

## Task 11: SKIP LOCKED 并发抢占与租约

本任务是任务队列的核心，也是本计划技术含量最高的部分。

**Files:**
- Create: `src/main/java/com/mymedia/jobs/JobClaimService.java`
- Test: `src/test/java/com/mymedia/jobs/JobClaimServiceTest.java`

**Interfaces:**
- Consumes: `JobRepository`、`Job`、`JobStatus`、`JobQueue`（Task 10）
- Produces: `class JobClaimService`（package-private）
  - `List<Job> claim(String owner, int batchSize, Duration leaseDuration)`
  - `int reclaimExpiredLeases()`

- [ ] **Step 1: 写会失败的并发测试**

`src/test/java/com/mymedia/jobs/JobClaimServiceTest.java`：

```java
package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class JobClaimServiceTest extends AbstractIntegrationTest {

    @Autowired
    JobClaimService claimService;

    @Autowired
    JobQueue jobQueue;

    @Test
    void claimedJobsBecomeRunningWithLease() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());

        List<Job> claimed = claimService.claim("worker-1", 10, Duration.ofMinutes(5));

        assertThat(claimed).extracting(Job::getId).contains(id);

        Job job = jobQueue.findById(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getLeaseOwner()).isEqualTo("worker-1");
        assertThat(job.getLeaseExpiresAt()).isNotNull();
        assertThat(job.getAttempts()).isEqualTo(1);
    }

    @Test
    void concurrentWorkersNeverClaimTheSameJob() throws Exception {
        int jobCount = 40;
        for (int i = 0; i < jobCount; i++) {
            jobQueue.enqueue("PREVIEW_GENERATE", "{\"n\":" + i + "}", null);
        }

        int workers = 4;
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Callable<List<Long>>> tasks = java.util.stream.IntStream.range(0, workers)
                    .<Callable<List<Long>>>mapToObj(w -> () ->
                            claimService.claim("worker-" + w, 20, Duration.ofMinutes(5))
                                    .stream().map(Job::getId).toList())
                    .toList();

            List<Future<List<Long>>> futures = pool.invokeAll(tasks);

            List<Long> allClaimed = futures.stream()
                    .flatMap(f -> {
                        try {
                            return f.get().stream();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            Set<Long> distinct = allClaimed.stream().collect(Collectors.toSet());

            // 核心断言：并发抢占的结果集必须互不相交。
            // 若 SKIP LOCKED 缺失，同一个任务会被多个 worker 同时拿到。
            assertThat(allClaimed).hasSize(distinct.size());
        }
    }

    @Test
    void expiredLeasesAreReclaimed() {
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());

        // 租约设为负时长，使其立即过期，模拟 worker 崩溃
        claimService.claim("dead-worker", 10, Duration.ofSeconds(-1));
        assertThat(jobQueue.findById(id).getStatus()).isEqualTo(JobStatus.RUNNING);

        int reclaimed = claimService.reclaimExpiredLeases();

        assertThat(reclaimed).isGreaterThanOrEqualTo(1);
        Job job = jobQueue.findById(id);
        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getLeaseOwner()).isNull();
    }

    @Test
    void jobsScheduledInFutureAreNotClaimed() {
        // markFailed 会把 scheduled_at 推到将来，这类任务不应被立即重新抢占
        Long id = jobQueue.enqueue("LIBRARY_SCAN", "{}", "dedup-" + UUID.randomUUID());
        claimService.claim("worker-1", 10, Duration.ofMinutes(5));
        claimService.recordFailure(id, "网络超时");

        List<Job> claimed = claimService.claim("worker-2", 10, Duration.ofMinutes(5));

        assertThat(claimed).extracting(Job::getId).doesNotContain(id);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=JobClaimServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`JobClaimService` 不存在。

- [ ] **Step 3: 实现抢占服务**

`src/main/java/com/mymedia/jobs/JobClaimService.java`：

```java
package com.mymedia.jobs;

import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
class JobClaimService {

    /** 首次重试等待 30 秒，其后每次翻倍：30s、60s、120s…… */
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private final JobRepository repository;

    JobClaimService(JobRepository repository) {
        this.repository = repository;
    }

    /**
     * 抢占一批待执行任务。
     *
     * <p>整个方法必须在一个事务内：{@code FOR UPDATE SKIP LOCKED} 持有的行锁
     * 只在事务期间有效。在同一事务内把状态改成 RUNNING，其他 worker 才看不到
     * 这些行。若把查询与状态更新拆到两个事务，中间的窗口会让任务被重复抢占。
     *
     * <p>{@code REQUIRES_NEW} 保证即使调用方已在事务中，抢占也独立提交，
     * 使租约尽快对其他 worker 可见。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<Job> claim(String owner, int batchSize, Duration leaseDuration) {
        Instant now = Instant.now();
        List<Job> jobs = repository.claimBatch(now, batchSize);
        Instant leaseExpiry = now.plus(leaseDuration);
        for (Job job : jobs) {
            job.markRunning(owner, leaseExpiry);
        }
        return jobs;
    }

    /**
     * 回收租约已过期的任务。worker 进程崩溃时不会有人把任务标记为失败，
     * 租约到期是唯一能察觉它已死的信号。
     *
     * @return 被回收的任务数
     */
    @Transactional
    int reclaimExpiredLeases() {
        return repository.reclaimExpiredLeases(Instant.now());
    }

    @Transactional
    void recordSuccess(Long jobId) {
        load(jobId).markSucceeded();
    }

    /**
     * 记录一次失败。未达最大尝试次数则按指数退避推迟重试，否则终结为 FAILED。
     */
    @Transactional
    void recordFailure(Long jobId, String error) {
        Job job = load(jobId);
        long multiplier = 1L << Math.min(job.getAttempts() - 1, 10);   // 上限约 8.5 小时
        Instant nextAttemptAt = Instant.now().plus(BASE_BACKOFF.multipliedBy(multiplier));
        job.markFailed(error, nextAttemptAt);
    }

    private Job load(Long jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new NotFoundException("找不到任务 id=" + jobId));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=JobClaimServiceTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 4, Failures: 0, Errors: 0`

**若 `concurrentWorkersNeverClaimTheSameJob` 失败**（出现重复 id），检查 `claimBatch` 的原生 SQL 是否漏了 `FOR UPDATE SKIP LOCKED`，以及 `claim` 方法是否带 `@Transactional`。这两者缺一，并发抢占就会重复。

- [ ] **Step 5: 写 ADR**

`docs/adr/ADR-003-用数据库任务表替代消息队列.md`：

```markdown
# ADR-003：用 PostgreSQL 任务表替代消息队列

## 状态
已接受（2026-08-17）

## 背景
扫描、刮削、预览生成、转码都是耗时的后台任务，需要一套排队与并发消费机制。

## 决策
用 PostgreSQL 的 `job` 表加 `SELECT ... FOR UPDATE SKIP LOCKED` 实现任务队列，
不引入 RabbitMQ 或 Kafka。

## 理由
- **`SKIP LOCKED` 提供了安全并发消费所需的全部语义**：多个 worker 并发查询时各自
  跳过已被锁住的行，拿到互不相交的任务集，且互不阻塞。这正是消息队列的核心能力。
- **任务历史可查询**。任务表天然支持"哪些任务失败了、失败原因是什么、重试了几次"
  这类查询。消息队列做同样的事要额外配一套持久化与监控。
- **任务与业务数据在同一个事务里**。入队与业务变更可以原子提交，不存在
  "业务写成功但消息没发出去"的双写问题。
- **少一个中间件**。项目的交付目标是 `docker compose up` 一键启动，
  每多一个中间件就多一份运维与理解成本。
- 本项目是单实例部署，任务量以每次扫描数千条计，远未触及需要专用消息中间件的规模。

## 后果
- 依赖轮询而非推送，任务从入队到开始执行有最长一个轮询周期的延迟。
  对分钟级的后台任务无影响。
- 高频轮询会给数据库带来持续负载。当前轮询间隔为 5 秒，配合部分索引
  `idx_job_claim`，单次查询成本极低。
- 若将来需要多实例部署，`SKIP LOCKED` 依然成立——它本就是为并发消费设计的。
  真正的瓶颈会先出现在数据库连接数上，届时再考虑迁移。

## 备选方案
- **RabbitMQ / Kafka**：能力足够，但为当前规模引入了额外的部署、监控与故障模式，
  且失去了任务与业务数据的事务一致性。
- **Spring `@Async` + 内存队列**：应用重启即丢失全部待执行任务，
  对"扫描一个大媒体库"这类长任务不可接受。
```

- [ ] **Step 6: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add src/main/java/com/mymedia/jobs src/test/java/com/mymedia/jobs docs/adr
git commit -m "feat: 实现 SKIP LOCKED 并发抢占与租约回收

抢占与状态更新必须同事务——行锁只在事务期间有效。
并发测试用 4 个 worker 抢 40 个任务，断言结果集互不相交。
决策记录见 ADR-003。"
```

---

## Task 12: JobHandler SPI 与调度器

**Files:**
- Create: `src/main/java/com/mymedia/jobs/JobHandler.java`
- Create: `src/main/java/com/mymedia/jobs/JobScheduler.java`
- Modify: `src/main/java/com/mymedia/MyMediaApplication.java`（启用 `@EnableScheduling`）
- Modify: `src/main/resources/application.yml`（任务队列配置项）
- Test: `src/test/java/com/mymedia/jobs/JobSchedulerTest.java`

**Interfaces:**
- Consumes: `JobClaimService`、`Job`、`JobQueue`（Task 10、11）
- Produces: `public interface JobHandler`
  - `String jobType()` — 该处理器负责的任务类型
  - `void handle(Job job) throws Exception` — 抛异常即视为失败并触发重试

  **这是 `jobs` 模块对外的 SPI。后续计划中的 `scan`、`metadata`、`preview` 模块各自实现它并注册为 Spring bean。**

- [ ] **Step 1: 写会失败的测试**

`src/test/java/com/mymedia/jobs/JobSchedulerTest.java`：

```java
package com.mymedia.jobs;

import com.mymedia.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import java.time.Duration;

@Import(JobSchedulerTest.TestHandlers.class)
class JobSchedulerTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue jobQueue;

    @Autowired
    JobScheduler scheduler;

    @Autowired
    RecordingHandler recordingHandler;

    @Autowired
    AlwaysFailingHandler failingHandler;

    @Test
    void dispatchesJobToMatchingHandler() {
        Long id = jobQueue.enqueue("TEST_RECORD", "{\"v\":42}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(recordingHandler.handled()).contains(id));
        assertThat(jobQueue.findById(id).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void failedJobIsRescheduledForRetry() {
        Long id = jobQueue.enqueue("TEST_FAIL", "{}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Job job = jobQueue.findById(id);
            // 首次失败后应退回 PENDING 等待退避重试，而不是直接判死
            assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(job.getAttempts()).isEqualTo(1);
            assertThat(job.getLastError()).contains("故意失败");
        });
    }

    @Test
    void jobWithNoHandlerFailsWithClearMessage() {
        Long id = jobQueue.enqueue("NO_SUCH_TYPE", "{}", "k" + UUID.randomUUID());

        scheduler.pollOnce();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jobQueue.findById(id).getLastError())
                        .contains("没有注册").contains("NO_SUCH_TYPE"));
    }

    @TestConfiguration
    static class TestHandlers {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        AlwaysFailingHandler alwaysFailingHandler() {
            return new AlwaysFailingHandler();
        }
    }

    static class RecordingHandler implements JobHandler {

        private final List<Long> handled = new CopyOnWriteArrayList<>();

        @Override
        public String jobType() {
            return "TEST_RECORD";
        }

        @Override
        public void handle(Job job) {
            handled.add(job.getId());
        }

        List<Long> handled() {
            return handled;
        }
    }

    static class AlwaysFailingHandler implements JobHandler {

        @Override
        public String jobType() {
            return "TEST_FAIL";
        }

        @Override
        public void handle(Job job) {
            throw new IllegalStateException("故意失败");
        }
    }
}
```

- [ ] **Step 2: 添加 Awaitility 测试依赖**

在 `pom.xml` 的 `<dependencies>` 中追加：

```xml
		<dependency>
			<groupId>org.awaitility</groupId>
			<artifactId>awaitility</artifactId>
			<scope>test</scope>
		</dependency>
```

版本由 Spring Boot 的 BOM 管理，不要写 `<version>`。

- [ ] **Step 3: 运行测试确认失败**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=JobSchedulerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "ERROR|cannot find symbol" t.log | head -10
```

Expected: 编译失败，`JobHandler` 与 `JobScheduler` 不存在。

- [ ] **Step 4: 定义 SPI**

`src/main/java/com/mymedia/jobs/JobHandler.java`：

```java
package com.mymedia.jobs;

/**
 * 任务处理器的服务提供接口。
 *
 * <p>其他模块通过实现本接口并注册为 Spring bean 来接管某一类任务，
 * {@code jobs} 模块不需要知道它们的存在——依赖方向是单向的。
 * 这使得新增一类后台任务无需修改调度器代码。
 */
public interface JobHandler {

    /** 本处理器负责的任务类型，与 {@code JobQueue.enqueue} 的 type 参数对应。 */
    String jobType();

    /**
     * 执行任务。抛出任何异常都视为失败，调度器会按指数退避安排重试，
     * 超过最大尝试次数后终结为 FAILED。
     */
    void handle(Job job) throws Exception;
}
```

- [ ] **Step 5: 实现调度器**

`src/main/java/com/mymedia/jobs/JobScheduler.java`：

```java
package com.mymedia.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobClaimService claimService;
    private final Map<String, JobHandler> handlersByType;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    JobScheduler(JobClaimService claimService,
                 List<JobHandler> handlers,
                 @Value("${mymedia.jobs.batch-size:5}") int batchSize,
                 @Value("${mymedia.jobs.lease-duration:PT10M}") Duration leaseDuration) {
        this.claimService = claimService;
        this.handlersByType = handlers.stream()
                .collect(Collectors.toMap(JobHandler::jobType, Function.identity()));
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.workerId = buildWorkerId();
        log.info("任务调度器启动，workerId={}，已注册处理器={}", workerId, handlersByType.keySet());
    }

    @Scheduled(fixedDelayString = "${mymedia.jobs.poll-interval:PT5S}")
    void poll() {
        pollOnce();
    }

    /** 供测试直接触发一轮轮询，避免依赖定时器时序。 */
    void pollOnce() {
        int reclaimed = claimService.reclaimExpiredLeases();
        if (reclaimed > 0) {
            log.warn("回收了 {} 个租约过期的任务", reclaimed);
        }

        List<Job> claimed = claimService.claim(workerId, batchSize, leaseDuration);
        for (Job job : claimed) {
            execute(job);
        }
    }

    private void execute(Job job) {
        JobHandler handler = handlersByType.get(job.getType());
        if (handler == null) {
            String message = "没有注册处理该类型的 JobHandler: " + job.getType();
            log.error(message);
            claimService.recordFailure(job.getId(), message);
            return;
        }
        try {
            handler.handle(job);
            claimService.recordSuccess(job.getId());
            log.debug("任务完成 id={} type={}", job.getId(), job.getType());
        } catch (Exception e) {
            log.warn("任务失败 id={} type={}，将按退避重试", job.getId(), job.getType(), e);
            claimService.recordFailure(job.getId(), describe(e));
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + ": " + (message == null ? "(无消息)" : message);
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
```

- [ ] **Step 6: 启用定时任务**

修改 `src/main/java/com/mymedia/MyMediaApplication.java`：

```java
package com.mymedia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MyMediaApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyMediaApplication.class, args);
    }
}
```

- [ ] **Step 7: 添加配置项**

在 `src/main/resources/application.yml` 末尾追加：

```yaml
mymedia:
  jobs:
    poll-interval: PT5S      # 轮询间隔
    batch-size: 5            # 单次抢占的任务数
    lease-duration: PT10M    # 租约时长，超时视为 worker 已死
```

- [ ] **Step 8: 运行测试确认通过**

```bash
cd /d/MyMedia && mvn -B -ntp test -Dtest=JobSchedulerTest -DfailIfNoTests=false > t.log 2>&1; echo "EXIT=$?"; grep -E "Tests run" t.log
```

Expected: `EXIT=0`，`Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 9: 提交**

```bash
cd /d/MyMedia
rm -f t.log
git add pom.xml src/main/java/com/mymedia src/main/resources/application.yml src/test/java/com/mymedia/jobs/JobSchedulerTest.java
git commit -m "feat: 添加 JobHandler SPI 与轮询调度器

JobHandler 是 jobs 模块对外的 SPI，其他模块实现它即可接管一类任务，
jobs 模块无需知道它们存在——依赖方向单向。"
```

---

## Task 13: 全量验证与交付文档

**Files:**
- Create: `docs/walkthrough/01-基础设施.md`
- Create: `README.md`

**Interfaces:**
- Consumes: 前 12 个任务的全部产出
- Produces: 可交付的阶段成果

- [ ] **Step 1: 运行全部测试**

```bash
cd /d/MyMedia && mvn -B -ntp verify > full.log 2>&1; echo "EXIT=$?"; grep -E "Tests run:|BUILD" full.log | tail -10
```

Expected: `EXIT=0`，`BUILD SUCCESS`，全部测试通过，无 Failures 无 Errors。

**这一步不通过就不要继续。** 逐个排查失败的测试，不要跳过。

- [ ] **Step 2: 确认架构图已生成**

```bash
ls -la /d/MyMedia/target/spring-modulith-docs/
```

Expected: 存在 `components.puml`、`module-shared.puml`、`module-user.puml`、`module-library.puml`、`module-jobs.puml` 及对应 `.adoc`。

- [ ] **Step 3: 手工冒烟测试**

```bash
cd /d/MyMedia && docker compose up -d && mvn -B -ntp spring-boot:run > run.log 2>&1 &
sleep 30
curl -s http://localhost:8080/actuator/health
curl -s -u admin:admin http://localhost:8080/api/libraries
```

Expected: health 返回 `{"status":"UP"}`；`/api/libraries` 在未创建 admin 账号时返回 `401`。

> **说明**：本计划尚未提供初始管理员账号的创建入口。这是 Task 14 的内容。

- [ ] **Step 4: 添加初始管理员引导**

`src/main/java/com/mymedia/user/AdminBootstrap.java`：

```java
package com.mymedia.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /**
     * 首次启动时创建初始管理员。已存在则跳过，因此重启是安全的。
     */
    @Bean
    ApplicationRunner createInitialAdmin(
            UserRegistrationService registrationService,
            UserAccountRepository repository,
            @Value("${mymedia.admin.username:admin}") String username,
            @Value("${mymedia.admin.password:admin}") String password) {

        return args -> {
            if (repository.existsByUsername(username)) {
                return;
            }
            registrationService.register(username, password, UserRole.ADMIN);
            log.warn("已创建初始管理员账号 '{}'，请立即修改默认密码", username);
        };
    }
}
```

在 `application.yml` 的 `mymedia` 节点下追加：

```yaml
  admin:
    username: admin
    password: admin      # 仅用于本地演示，生产部署必须通过环境变量覆盖
```

- [ ] **Step 5: 重新冒烟测试**

```bash
cd /d/MyMedia && curl -s -u admin:admin http://localhost:8080/api/libraries
curl -s -u admin:admin -X POST http://localhost:8080/api/libraries \
  -H 'Content-Type: application/json' \
  -d '{"name":"电影","domain":"VIDEO","rootPath":"/media/movies"}'
```

Expected: 第一条返回 `[]`；第二条返回 `201` 与创建的媒体库 JSON。

- [ ] **Step 6: 写讲解文档**

`docs/walkthrough/01-基础设施.md`，面向项目所有者，必须覆盖以下六个问题（每个问题至少一段，配代码引用）：

1. **Spring Modulith 到底做了什么？** 解释"包即模块"、public/package-private 如何成为模块边界、`ApplicationModules.verify()` 在什么情况下会失败。举一个具体的越界例子。
2. **为什么域分区要用复合外键？** 解释 CHECK 无法跨表、`UNIQUE (id, domain)` 为何是必需的锚点。指向 ADR-001。
3. **`FOR UPDATE SKIP LOCKED` 是怎么工作的？** 解释行锁、为什么抢占和状态更新必须同事务、若拆成两个事务会发生什么。指向 ADR-003。
4. **租约解决的是什么问题？** 解释 worker 崩溃后任务卡在 RUNNING 的场景，以及为什么超时是唯一可行的探测手段。
5. **为什么无权访问的媒体库返回 404 而不是 403？** 解释资源存在性泄露。
6. **`@PreAuthorize` 为什么需要 `@EnableMethodSecurity`？** 解释这是一个静默失效的坑，以及我们用哪个测试守住它。

- [ ] **Step 7: 写 README**

`README.md` 至少包含：

- 项目简介与定位（自托管媒体库，视频域 / 图片域分区）
- 技术栈与版本（Spring Boot 4.1.0 / Java 25 / Spring Modulith 2.1.0 / PostgreSQL 17）
- 快速开始：`docker compose up -d` 然后 `mvn spring-boot:run`
- 架构说明，嵌入 `target/spring-modulith-docs/components.puml` 渲染出的模块图
- 已实现功能清单与后续路线图（指向 spec §11）
- ADR 索引

- [ ] **Step 8: 最终提交**

```bash
cd /d/MyMedia
rm -f full.log run.log t.log build.log
git add -A
git commit -m "feat: 完成基础设施阶段

可启动的服务、账号认证、媒体库管理（含域分区的数据库级强制）、
基于 SKIP LOCKED 的持久化任务队列。附讲解文档与 README。"
```

---

## Self-Review

**1. Spec 覆盖检查**

| spec 章节 | 覆盖任务 |
|---|---|
| §3.1 版本矩阵 | Task 1（内联实测 pom） |
| §3.2 Boot 4.x 改名 | Global Constraints |
| §3.3 虚拟线程 / Flyway / Security | Task 1（`application.yml`）、Task 6 |
| §4.2 模块清单与依赖规则 | Task 3（本计划范围内的 4 个模块） |
| §5.1 域分区数据层强制 | Task 7 + ADR-001 |
| §6.2 `users` | Task 5 |
| §6.2 `libraries` / `library_access` | Task 7、8 |
| §6.2 `job` | Task 10 |
| §7.5 任务队列 | Task 10、11、12 + ADR-003 |
| §7.7 pg_trgm 验收 | Task 2、Task 4 |
| §9 测试策略（架构 / 单元 / 集成 / API） | Task 3、4–12 |
| §10 交付物（讲解文档、ADR、README） | Task 6、7、11、13 |
| 路线图 P0 / P1 / P2 | Task 1–4 / 5–9 / 10–12 |

**未覆盖（属于后续计划，非遗漏）**：§6.2 的 `scanned_file`、`share_link`、`derived_asset`、`upload_session`、`tag` 均在计划 02 及以后；§6.3、§6.4 领域模型在计划 03、04；§7.1–§7.4、§7.6 在各自计划。

**2. 占位符扫描**：已通过。全部步骤含可直接执行的命令或完整代码，无 "TBD"、"类似 Task N"、"添加适当的错误处理"。

**3. 类型一致性检查**

| 标识符 | 定义于 | 被引用于 | 一致 |
|---|---|---|---|
| `UserAccount`（非 `User`） | Task 5 | Task 6、8、9 | ✓ |
| `UserRole.ADMIN` / `.USER` | Task 5 | Task 6、8、9 | ✓ |
| `UserRegistrationService.register(String, String, UserRole)` | Task 5 | Task 6、8、9、13 | ✓ |
| `UserQueryService.findByUsername` / `.getById` | Task 6 | Task 8、9 | ✓ |
| `UserAccountRepository`（package-private） | Task 5 | 仅 `user` 模块内（Task 6、13） | ✓ |
| `LibraryDomain.VIDEO` / `.IMAGE` | Task 7 | Task 8、9 | ✓ |
| `MediaLibrary` + `getId/getName/getDomain/getRootPath/isEnabled` | Task 7 | Task 8、9 | ✓ |
| `LibraryService.create(String, LibraryDomain, String)` | Task 7 | Task 8、9 | ✓ |
| `LibraryAccessService.canAccess/grant/revoke/accessibleLibraries` | Task 8 | Task 9 | ✓ |
| `NotFoundException(String)` | Task 3 | Task 6、7、9、10、11 | ✓ |
| `JobStatus` 五个枚举值 | Task 10 | Task 11、12 | ✓ |
| `Job` + `markRunning/markSucceeded/markFailed` | Task 10 | Task 11 | ✓ |
| `JobQueue.enqueue(String, String, String)` / `findById` / `markSucceeded` | Task 10 | Task 11、12 | ✓ |
| `JobClaimService.claim/reclaimExpiredLeases/recordSuccess/recordFailure` | Task 11 | Task 12 | ✓ |
| `JobHandler.jobType()` / `handle(Job)` | Task 12 | 后续计划的 scan/metadata/preview 模块 | ✓ |

一处已在编写中修正：Task 11 的测试用到了 `claimService.recordFailure`，该方法原本只在 Task 12 出现；已提前到 Task 11 Step 3 一并实现。
