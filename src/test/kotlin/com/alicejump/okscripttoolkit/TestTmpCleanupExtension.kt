package com.alicejump.okscripttoolkit

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * 整轮测试结束后统一删除 [TestTmp.root]。
 *
 * 为什么不用 `@AfterAll`：它只覆盖**单个测试类**，且断言失败时会被跳过 ——
 * 而这里要的恰恰是"所有测试都跑完（哪怕是跑挂的）再删一次"。
 *
 * 为什么不用 `LauncherSessionListener`：它属于 `junit-platform-launcher`，
 * 那个 jar 在本项目的测试**编译**类路径上没有保证（只有 jupiter-api / engine 是确定的）。
 * 这里改用 `junit-jupiter-api` 里稳定的 [ExtensionContext.Store.CloseableResource]：
 * 把清理器挂在**根** context 的 store 上，store 在整轮测试最末尾关闭，
 * 于是 `close()` 恰好执行一次、且在最后。
 *
 * 注册方式：`META-INF/services/org.junit.jupiter.api.extension.Extension` +
 * `junit-platform.properties` 里的自动注册开关，因此对每个测试类都生效，
 * 无需改任何测试类的注解。Gradle 直接跑、IDE 里跑都覆盖。
 */
class TestTmpCleanupExtension : Extension, BeforeAllCallback {

    override fun beforeAll(context: ExtensionContext) {
        val store = context.root.getStore(NAMESPACE)
        // beforeAll 会为每个容器各调一次，只在根 store 里登记一次。
        if (store.get(CLEANUP_KEY) != null) return
        store.put(CLEANUP_KEY, Cleaner())
    }

    private class Cleaner : ExtensionContext.Store.CloseableResource {
        override fun close() {
            TestTmp.clean()
        }
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace =
            ExtensionContext.Namespace.create(TestTmpCleanupExtension::class.java)
        const val CLEANUP_KEY = "testTmpCleanup"
    }
}
