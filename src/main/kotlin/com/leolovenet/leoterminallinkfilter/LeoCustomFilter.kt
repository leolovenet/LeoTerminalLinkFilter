package com.leolovenet.leoterminallinkfilter

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.Filter.ResultItem
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class LeoCustomFilter(private val project: Project) : Filter {
    private val logger = Logger.getInstance(LeoCustomFilter::class.java)
    private val targetFileCache = mutableMapOf<String, VirtualFile>()

    //`        at com/abc/def/ghi/j    xxx    (Test.java:5)`
    //` L23    at sources/com/abc/def/ghi/j.java  com.vivo.hybrid.main.c#a  (SourceFile)  [overloads: 1|2|3|4|5|6|7]`
    private val regex = Regex(""" {2,}\d+ (\S+) {2,}(\S+) {2,}\(([^)]+)\)\s*(?:\[overloads:\s*([\d |]+)\])?""")

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val matchResult = regex.find(line) ?: return null // 如果没有匹配，返回 null

        val classAndMethod = matchResult.groupValues[2].split("#", limit = 2)
        val fileInfo = FileInfo(
            path = matchResult.groupValues[1],
            clazzName = classAndMethod.first(),
            methodName = classAndMethod.getOrElse(1) { "" },
            sourceName = matchResult.groupValues[3].split(":").first(),
            overloads = parseOverloads(matchResult)
        )
        logger.debug("LeoTerminalLinkFilter matched line: path=${fileInfo.path}, class=${fileInfo.clazzName}, method=${fileInfo.methodName}, source=${fileInfo.sourceName}, overloads=${fileInfo.overloads.size}")

        var targetFile = targetFileCache[fileInfo.cacheKey]
        if (targetFile == null || !targetFile.exists()) {
            targetFile = findExactMatch(fileInfo)
            if (targetFile == null) {
                targetFile = findFuzzyMatch(fileInfo)
            }
            if (targetFile != null) {
                targetFileCache[fileInfo.cacheKey] = targetFile
            }
        }
        logger.debug("LeoTerminalLinkFilter resolved target file: ${targetFile?.path ?: "null"}")

        val resultItems = mutableListOf<ResultItem>()
        if (targetFile != null) {
            val lineNumbers = findMethodLineNumber(targetFile, fileInfo)
            logger.debug("LeoTerminalLinkFilter method line candidates: ${lineNumbers.joinToString()}")
            for ((idx, _) in lineNumbers.withIndex()) {
                val hyperlinkInfo = MethodHyperlinkInfo(targetFile, fileInfo, idx)
                if (idx == 0) {
                    // 计算链接在原始行中的高亮范围
                    val range = matchResult.groups[1]!!.range
                    val startOffset = entireLength - line.length + range.first
                    val endOffset = entireLength - line.length + range.last + 1
                    resultItems.add(ResultItem(startOffset, endOffset, hyperlinkInfo))
                }
                if (lineNumbers.size > 1 && fileInfo.overloads.size > idx) {
                    // 计算链接在原始行中的高亮范围
                    val overload = fileInfo.overloads[idx]
                    val startOffset = entireLength - line.length + overload.range.first
                    val endOffset = entireLength - line.length + overload.range.last + 1
                    resultItems.add(ResultItem(startOffset, endOffset, hyperlinkInfo))
                }
            }
        }
        logger.debug("LeoTerminalLinkFilter result item count: ${resultItems.size}")
        return if (resultItems.isNotEmpty()) Filter.Result(resultItems) else null
    }

    private fun findMethodLineNumber(file: VirtualFile, info: FileInfo): List<Int> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return listOf(0)
        val lines = document.text.lines()

        // 普通方法必须有返回类型；构造函数没有返回类型，但名称必须匹配类短名。
        val methodDefRegex = Regex(
            """^\s*(?:(?:public|protected|private|static|final|abstract|synchronized|native|strictfp|\/\*.*\*\/)\s+)*(?:<[^>]+>\s+)?(?:(?!(?:public|protected|private|static|final|abstract|synchronized|native|strictfp)\b)[\w\$\.\[\]\<\>\?,]+\s+)+\b([A-Za-z_][\w\$]*)\s*\([^;]*$""",
        )
        val constructorDefRegex = Regex(
            """^\s*(?:(?:public|protected|private|strictfp|\/\*.*\*\/)\s+)*(?:<[^>]+>\s+)?\b([A-Za-z_][\w\$]*)\s*\([^;]*$""",
        )

        val lineNumbers = mutableListOf<Int>()
        for ((index, line) in lines.withIndex()) {
            val methodMatch = methodDefRegex.find(line)
            val constructorMatch = constructorDefRegex.find(line)
            val definedName = methodMatch?.groupValues?.get(1)
                ?: constructorMatch?.groupValues?.get(1)?.takeIf { info.isConstructor && it == info.clazzShortName }
                ?: continue
            // 直接匹配到目标方法
            if (definedName == info.methodName || (info.isConstructor && definedName == info.clazzShortName)) {
                lineNumbers.add(index)
                continue
            }

            // 向上查找，是否为注释行， 如果是的话， 检查是否有 ` renamed from: xxx ` 注释
            if (index > 0) {
                val renameRegex = Regex("""\s+renamed from:\s*([^,\s]+)""")
                // scan a few lines above for a ` renamed from: ... */` comment
                for (i in (index - 1) downTo maxOf(0, index - 6)) {
                    val prevLine = lines[i].trim()
//                    if (prevLine.contains("}")) {
//                        break
//                    }
                    val renameMatch = renameRegex.find(prevLine)
                    if (renameMatch != null) {
                        val originalName = renameMatch.groupValues[1]
                        if (originalName == info.methodName || originalName.split(Regex("""[,\s/]+""")).contains(info.methodName)) {
                            lineNumbers.add(index)
                            break
                        } else {
                            break // 已经遇到一个重命名注释，但不匹配，停止继续向上查找
                        }
                    }
                }
            }
        }
        return if (lineNumbers.isEmpty()) listOf(0) else lineNumbers
    }

    private fun parseOverloads(matchResult: MatchResult): List<Overload> {
        val overloadsGroup = matchResult.groups[4] ?: return emptyList()
        return Regex("""\d+""").findAll(overloadsGroup.value).map { overloadMatch ->
            val start = overloadsGroup.range.first + overloadMatch.range.first
            val end = overloadsGroup.range.first + overloadMatch.range.last
            Overload(start..end)
        }.toList()
    }

    private fun findExactMatch(info: FileInfo): VirtualFile? {
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        for (root in contentRoots) {
            val file = VfsUtil.findRelativeFile(root, *info.path.split('/').toTypedArray())
            if (file != null) {
                return file
            }
        }
        return null
    }

    private fun findFuzzyMatch(info: FileInfo): VirtualFile? {
        val segments = info.path.split('/')
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        return findDirFuzzyMatchRecursive(contentRoots, segments, info)
    }

    private fun findDirFuzzyMatchRecursive(
        searchDirs: Array<VirtualFile>,
        segments: List<String>,
        info: FileInfo
    ): VirtualFile? {
        if (segments.isEmpty()) {
            return null
        }

        val currentSegment = segments.first()
        val remainingSegments = segments.drop(1)
        val potentialMatches = mutableListOf<Match>()

        // 最后一级目录，直接在当前目录下查找文件
        if (remainingSegments.isEmpty()) {
            val sourceNameParts = info.sourceName.split(".")
            val sourceNameWithOutExt = sourceNameParts.first()
            val sourceNameIsJava = sourceNameParts.lastOrNull() == "java"
            // 创建待遍历文件列表, 源文件名开头的放前面
            val children = mutableListOf<VirtualFile>()
            for (dir in searchDirs) {
                if (!dir.isDirectory) continue
                for (child in dir.children) {
                    if (!child.isDirectory && child.extension == "java") {
                        if ((sourceNameIsJava && child.name.startsWith(sourceNameWithOutExt))
                            || child.name.startsWith(currentSegment)
                        ) {
                            children.add(0, child)
                        } else {
                            children.add(child)
                        }
                    }
                }
            }

            if (children.isEmpty()) return null

            return children.chunked(10) { it.toTypedArray() }
                .firstNotNullOfOrNull { chunk -> findJavaFile(chunk, currentSegment, info) }
        } else {
            // 非最后一级目录，先在当前目录下查找潜在匹配目录
            for (dir in searchDirs) {
                if (!dir.isDirectory) continue
                for (child in dir.children) {
                    if (!child.isDirectory) continue
                    val childName = child.name
                    when {
                        childName == currentSegment -> potentialMatches.add(Match(child, 1)) // 1分: 完全匹配
                        childName.endsWith(currentSegment) -> potentialMatches.add(Match(child, 2)) // 2分: 后缀匹配
                        // 可以根据需要添加其他匹配规则，例如 contains (3分)
                    }
                }
            }
        }

        if (potentialMatches.isEmpty()) {
            return null
        }

        // 排序，分数越低越优先
        potentialMatches.sortBy { it.score }

        // 遍历排序后的最佳匹配项
        for (match in potentialMatches) {
            if (remainingSegments.isEmpty()) {
                return match.file
            } else {
                // 还有后续路径段，继续递归
                // 下一轮只在当前匹配到的目录中搜索
                val result = findDirFuzzyMatchRecursive(arrayOf(match.file), remainingSegments, info)
                if (result != null) {
                    return result
                }
                // 如果这个分支没找到，继续尝试下一个潜在匹配项
            }
        }

        return null
    }

    private fun findJavaFile(children: Array<VirtualFile>, currentSegment: String, info: FileInfo): VirtualFile? {
        // 如果没有找到，查找目录下的所有文件, 如果存在字符串, ` renamed from: $info.clazz,` 则返回
        for (child in children) {
            val document = FileDocumentManager.getInstance().getDocument(child) ?: continue
            val text = document.text
            val regex = Regex("""\s+renamed from:\s*${Regex.escape(info.clazzName)}[\s,*/]+""")
            if (regex.containsMatchIn(text)) {
                return child
            }
        }
        // 如果仍然没有找到
        // 按文件名匹配
        for (child in children) {
            if (child.name == currentSegment) {
                return child
            }
        }
        // 最后尝试按源文件名匹配
        for (child in children) {
            if (child.name == info.sourceName) {
                return child
            }
        }

        return null
    }

    private inner class MethodHyperlinkInfo(
        private val file: VirtualFile,
        private val info: FileInfo,
        private val overloadIndex: Int
    ) : HyperlinkInfo {
        override fun navigate(project: Project) {
            val latestLineNumbers = findMethodLineNumber(file, info)
            val lineNumber = latestLineNumbers.getOrElse(overloadIndex) { latestLineNumbers.firstOrNull() ?: 0 }
            OpenFileHyperlinkInfo(project, file, lineNumber).navigate(project)
        }
    }

    private data class Match(val file: VirtualFile, val score: Int)
    private data class Overload(val range: IntRange)
    private data class FileInfo(
        val path: String,
        val clazzName: String,
        val methodName: String,
        val sourceName: String,
        val overloads: List<Overload>
    ) {
        val clazzShortName: String = clazzName.substringAfterLast('.').substringAfterLast('$')
        val isConstructor: Boolean = methodName == "<init>" || methodName == clazzShortName
        val cacheKey: String = listOf(path, clazzName, sourceName).joinToString("#")
    }
}
