// Cloud Agent の install はコンパイルしない。dependencies タスクはメタデータしか落とさないので、
// 解決できる configuration の jar をここで取る。
gradle.beforeProject {
    afterEvaluate {
        tasks.register("resolveDependencies") {
            doLast {
                configurations
                    .filter { it.isCanBeResolved }
                    .forEach { it.resolve() }
            }
        }
    }
}
