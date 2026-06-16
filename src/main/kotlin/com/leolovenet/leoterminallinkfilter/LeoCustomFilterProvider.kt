package com.leolovenet.leoterminallinkfilter

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.project.Project

class LeoCustomFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> {
        // 在这里创建并返回你的自定义 Filter 实例
        return arrayOf(LeoCustomFilter(project))
    }
}