package org.example.emm

import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType

class MyRefactorAction : AnAction("Enhanced Move Method") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 클래스 이름 검색 팝업 창 표시
        val model = GotoClassModel2(project)
        ChooseByNamePopup
            .createPopup(project, model, null)
            .invoke(object : ChooseByNamePopupComponent.Callback() {
                override fun elementChosen(element: Any?) {
                    handleElementChosen(e, element as PsiClass)
                }
            }, ModalityState.current(), true)
    }

    private fun handleElementChosen(e: AnActionEvent, targetClass: PsiClass) {
        val project = e.project ?: return
        val currentMethod = findMethodAtCaret(e) ?: return
        val sourceClass = currentMethod.containingClass ?: return

        val methodDependenciesMap = findMethodDependenciesWithExclusivity(currentMethod, sourceClass)
        val methodsToMove = methodDependenciesMap.filter { it.value }.keys.toSet()
        val externalDependencies = methodDependenciesMap.filter { !it.value }.keys.toSet()

        val allMethodsToMove = mutableListOf<PsiMethod>().apply {
            add(currentMethod)
            addAll(methodsToMove)
        }

        val usages = findUsages(project, allMethodsToMove)

        if (usages.isNotEmpty() || externalDependencies.isNotEmpty()) {
            if (!confirmMethodMoveFromDialog(project, usages, methodsToMove, externalDependencies)) {
                return
            }
        }

        val factory = JavaPsiFacade.getElementFactory(project)
        val orderedMethodsToMove = sourceClass.methods.filter { it in allMethodsToMove }
        runWriteCommandAction(project) {
            if (externalDependencies.isNotEmpty<PsiMethod>()) {
                adjustMethodForExternalDependencies(
                    currentMethod,
                    sourceClass,
                    externalDependencies,
                    factory
                )
            } else {
                factory.createMethodFromText(currentMethod.text, null)
            }

            for (methodToCopy in orderedMethodsToMove) {
                val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)
                targetClass.add(methodCopy)
            }

            for (methodToDelete in orderedMethodsToMove.reversed()) {
                methodToDelete.delete()
            }

            if (externalDependencies.isNotEmpty()) {
                val file = targetClass.containingFile as? PsiJavaFile
                if (file != null) {
                    addImportIfNeeded(file, sourceClass.qualifiedName ?: "")
                }
            }
        }
        val callsToUpdate = collectMethodCalls(usages,
            allMethodsToMove.map { it.name }, sourceClass.name ?: ""
        )
        if (callsToUpdate.isNotEmpty<MethodCallInfo>()) {
            updateMethodCalls(
                project,
                callsToUpdate,
                targetClass,
                sourceClass,
                externalDependencies.isNotEmpty()
            )
        }
        val movedMethodsCount = allMethodsToMove.size
        val externalDepsCount = externalDependencies.size
        val movedMethodsMessage = if (movedMethodsCount > 1) {
            "메서드 '${currentMethod.name}' 및 관련된 ${movedMethodsCount - 1}개의 메서드가"
        } else {
            "메서드 '${currentMethod.name}'이(가)"
        }
        val externalDepsMessage = if (externalDepsCount > 0) {
            "\n\n${externalDepsCount}개의 메서드는 다른 메서드에서도 사용되므로 이동되지 않았으며, 대신 소스 클래스를 통해 호출됩니다."
        } else ""
        Messages.showMessageDialog(
            project,
            "${movedMethodsMessage} '${sourceClass.name}'에서 '${targetClass.name}'으로 이동되었습니다.${externalDepsMessage}",
            "메서드 이동 완료",
            Messages.getInformationIcon()
        )
    }

    private fun findUsages(project: Project, methods: List<PsiMethod>): MutableList<PsiElement> {
        val usages = mutableListOf<PsiElement>()
        val searchScope = GlobalSearchScope.projectScope(project)

        for (method in methods) {
            ReferencesSearch.search(method, searchScope).forEach { reference ->
                usages.add(reference.element)
            }
        }
        return usages
    }

    private fun confirmMethodMoveFromDialog(
        project: Project,
        usages: List<PsiElement>,
        methodsToMove: Set<PsiMethod>,
        externalDependencies: Set<PsiMethod>
    ): Boolean {
        val usageClasses = usages.mapNotNull {
            it.parentOfType<PsiClass>(false)?.qualifiedName
        }.distinct()

        val movableMethodsMessage = if (methodsToMove.isNotEmpty()) {
            "\n\n이 메소드와 함께 다음 의존 메소드들이 이동됩니다:\n" +
                    methodsToMove.joinToString("\n") { it.name }
        } else ""

        val externalDependenciesMessage = if (externalDependencies.isNotEmpty()) {
            "\n\n다음 메소드들은 다른 메소드에서도 사용되므로 이동되지 않습니다:\n" +
                    externalDependencies.joinToString("\n") { it.name } +
                    "\n(대신 소스 클래스 참조를 통해 호출됩니다)"
        } else ""

        val usagesMessage = if (usageClasses.isNotEmpty()) {
            "이 메소드는 다음 클래스에서 사용되고 있습니다:\n" +
                    usageClasses.joinToString("\n")
        } else ""

        val message = listOf(usagesMessage, movableMethodsMessage, externalDependenciesMessage)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n") + "\n\n계속 진행하시겠습니까?"

        val result = Messages.showYesNoDialog(
            project,
            message,
            "메소드 이동 확인",
            Messages.getQuestionIcon()
        )

        return result == Messages.YES
    }

    /**
     * 언제 이 Action이 활성화되는지 결정합니다.
     * 이 경우, 커서 위치가 메소드안인지 확인합니다.
     */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val caret = editor?.caretModel?.currentCaret
        val offset = caret?.offset
        val element = psiFile?.findElementAt(offset ?: -1)
        var method = element?.parentOfType<PsiMethod>(true)
        e.presentation.isEnabledAndVisible = method != null
    }

    private fun findMethodAtCaret(e: AnActionEvent): PsiMethod? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        return element.parentOfType<PsiMethod>(true)
    }

    private fun findMethodDependenciesWithExclusivity(
        method: PsiMethod,
        sourceClass: PsiClass
    ): Map<PsiMethod, Boolean> {
        val dependencies = mutableMapOf<PsiMethod, Boolean>() // 메소드 -> 독점적으로 사용되는지 여부
        val processedMethods = mutableSetOf<PsiMethod>()
        val allSourceMethods = sourceClass.methods.toSet()

        // 먼저 소스 클래스의 모든 메소드 간의 호출 그래프 구성
        val methodCallGraph = mutableMapOf<PsiMethod, MutableSet<PsiMethod>>()
        for (srcMethod in allSourceMethods) {
            methodCallGraph[srcMethod] = mutableSetOf()
            val methodCalls = PsiTreeUtil.findChildrenOfType(srcMethod, PsiMethodCallExpression::class.java)
            for (methodCall in methodCalls) {
                val resolveResult = methodCall.methodExpression.advancedResolve(false)
                val calledMethod = resolveResult.element as? PsiMethod ?: continue
                val calledMethodClass = calledMethod.containingClass ?: continue

                if (calledMethodClass == sourceClass) {
                    methodCallGraph[srcMethod]?.add(calledMethod)
                }
            }
        }

        // 특정 메소드가 타겟 메소드와 그 의존성 메소드들에서만 호출되는지 확인하는 함수
        fun isOnlyCalledByTargetMethodChain(calledMethod: PsiMethod, visitedMethods: MutableSet<PsiMethod>): Boolean {
            for (srcMethod in allSourceMethods) {
                if (srcMethod == method || srcMethod in visitedMethods) continue
                if (calledMethod in methodCallGraph[srcMethod].orEmpty()) {
                    return false // 대상 메소드 체인 외부에서 호출됨
                }
            }
            return true
        }

        fun processMethod(currentMethod: PsiMethod, visited: MutableSet<PsiMethod>) {
            if (currentMethod in processedMethods) return
            processedMethods.add(currentMethod)
            visited.add(currentMethod)

            // 메소드 내에서 호출하는 다른 메소드들 찾기
            val methodCalls = methodCallGraph[currentMethod].orEmpty()

            for (calledMethod in methodCalls) {
                if (calledMethod != method && calledMethod !in dependencies.keys) {
                    // 이 메소드가 대상 메소드와 그 의존성 메소드에서만 배타적으로 사용되는지 확인
                    val isExclusiveToTargetChain = isOnlyCalledByTargetMethodChain(calledMethod, visited)
                    dependencies[calledMethod] = isExclusiveToTargetChain
                    if (isExclusiveToTargetChain) {
                        processMethod(calledMethod, visited.toMutableSet())
                    }
                }
            }
        }

        processMethod(method, mutableSetOf())
        return dependencies
    }

    private fun adjustMethodForExternalDependencies(
        method: PsiMethod,
        sourceClass: PsiClass,
        externalDependencies: Set<PsiMethod>,
        factory: PsiElementFactory
    ): PsiMethod {
        if (externalDependencies.isEmpty()) return method

        // 원본 클래스 파라미터 이름 생성
        val sourceClassName = sourceClass.name ?: "sourceClass"
        val sourceParamName = sourceClassName.decapitalize()

        // 메소드 복사본 생성
        val methodCopy = factory.createMethodFromText(method.text, null)

        // 메소드 본문에서 외부 의존성 메소드 호출을 수정
        val methodCallExpressions = PsiTreeUtil.findChildrenOfType(methodCopy, PsiMethodCallExpression::class.java)

        for (methodCall in methodCallExpressions) {
            val resolveResult = methodCall.methodExpression.advancedResolve(false)
            val calledMethod = resolveResult.element as? PsiMethod ?: continue

            if (calledMethod in externalDependencies) {
                // 호출식 구조 분석
                val oldExpr = methodCall.methodExpression
                val methodName = oldExpr.referenceName ?: continue

                // 원본 클래스 참조를 통한 메소드 호출로 변경
                val newExprText = "$sourceParamName.$methodName"
                val qualifierExpression = oldExpr.qualifierExpression

                if (qualifierExpression == null) {
                    // 직접 호출(this.method() 또는 method())인 경우
                    val newExpr = factory.createExpressionFromText(newExprText, methodCall)
                    oldExpr.replace(newExpr)
                }
                // qualifier가 this인 경우도 처리 필요
                else if (qualifierExpression.text == "this") {
                    val newExpr = factory.createExpressionFromText(newExprText, methodCall)
                    oldExpr.replace(newExpr)
                }
            }
        }

        // 메소드 파라미터 목록에 원본 클래스 추가
        val parameterList = methodCopy.parameterList
        val sourceClassParam = factory.createParameter(
            sourceParamName,
            PsiType.getTypeByName(
                sourceClass.name ?: "Object",
                sourceClass.project,
                GlobalSearchScope.allScope(sourceClass.project)
            )
        )
        val newParameterList = factory.createParameterList(
            parameterList.parameters.map { it.name }.plus(sourceClassParam.name).toTypedArray(),
            parameterList.parameters.map { it.type }.plus(sourceClassParam.type).toTypedArray()
        )
        parameterList.replace(newParameterList)

        return methodCopy
    }

    private data class MethodCallInfo(
        val element: PsiElement,
        val containingClass: PsiClass,
        val originalQualifier: String?
    )

    private fun collectMethodCalls(
        usages: List<PsiElement>,
        methodNames: List<String>,
        sourceClassName: String
    ): List<MethodCallInfo> {
        val result = mutableListOf<MethodCallInfo>()

        for (usage in usages) {
            val methodCall = usage.parentOfType<PsiMethodCallExpression>(false)
            if (methodCall != null) {
                val containingClass = usage.parentOfType<PsiClass>(false) ?: continue

                // 현재 호출 형태 분석 (e.g., schoolRepository.deleteStudent(id))
                val qualifierExpression = methodCall.methodExpression.qualifierExpression
                val qualifier = qualifierExpression?.text

                result.add(MethodCallInfo(methodCall, containingClass, qualifier))
            }
        }

        return result
    }

    private fun updateMethodCalls(
        project: Project,
        callsToUpdate: List<MethodCallInfo>,
        targetClass: PsiClass,
        sourceClass: PsiClass,
        needsSourceClassParam: Boolean
    ) {
        val targetClassName = targetClass.name ?: return
        val targetQualifiedName = targetClass.qualifiedName ?: return
        val sourceClassName = sourceClass.name ?: return

        for (call in callsToUpdate) {
            val needsFieldInjection = call.originalQualifier != null &&
                    !hasFieldOfType(call.containingClass, targetQualifiedName)

            if (needsFieldInjection) {
                // 필드 추가가 필요한 경우 사용자에게 접근 제어자 선택 요청
                val accessModifier = promptForAccessModifier(project)
                if (accessModifier != null) {
                    runWriteCommandAction(project) {
                        // 필드 추가
                        addFieldToClass(call.containingClass, targetClass, accessModifier)

                        // import 문 추가
                        addImportIfNeeded(call.containingClass.containingFile as PsiJavaFile, targetQualifiedName)

                        // 메소드 호출 업데이트
                        val variableName = targetClassName.decapitalize()
                        updateMethodCallWithSourceClassParam(
                            call.element as PsiMethodCallExpression,
                            variableName,
                            sourceClass,
                            needsSourceClassParam,
                            call.containingClass == sourceClass
                        )
                    }
                }
            } else if (call.originalQualifier != null) {
                // 이미 필드가 있는 경우, 호출만 업데이트
                runWriteCommandAction(project) {
                    val variableName = targetClassName.decapitalize()
                    updateMethodCallWithSourceClassParam(
                        call.element as PsiMethodCallExpression,
                        variableName,
                        sourceClass,
                        needsSourceClassParam,
                        call.containingClass == sourceClass
                    )
                }
            }
        }
    }

    private fun hasFieldOfType(containingClass: PsiClass, typeName: String): Boolean {
        return containingClass.fields.any { field ->
            field.type.canonicalText == typeName
        }
    }

    private fun promptForAccessModifier(project: Project): String? {
        val options = arrayOf("private final", "public", "public final", "private")
        val result = Messages.showChooseDialog(
            project,
            "새 필드의 접근 제어자를 선택하세요",
            "접근 제어자 선택",
            Messages.getQuestionIcon(),
            options,
            options[0]
        )

        return if (result >= 0) options[result] else null
    }

    private fun addFieldToClass(
        containingClass: PsiClass,
        fieldType: PsiClass,
        accessModifier: String
    ) {
        val factory = JavaPsiFacade.getElementFactory(containingClass.project)
        val fieldName = fieldType.name?.decapitalize() ?: return
        val fieldText = "$accessModifier ${fieldType.name} $fieldName;"
        val field = factory.createFieldFromText(fieldText, containingClass)

        // 인스턴스 필드들 중 마지막 위치 찾기
        val lastInstanceField = containingClass.fields.lastOrNull { !it.hasModifierProperty(PsiModifier.STATIC) }

        if (lastInstanceField != null) {
            // 마지막 인스턴스 필드 뒤에 추가
            containingClass.addAfter(field, lastInstanceField)
        } else {
            // 인스턴스 필드가 없는 경우 - 클래스 맨 앞에 추가
            val anchor = containingClass.lBrace
            if (anchor != null) {
                containingClass.addAfter(field, anchor)
            } else {
                // 중괄호가 없는 경우(인터페이스 등) 그냥 추가
                containingClass.add(field)
            }
        }
    }

    private fun addImportIfNeeded(file: PsiJavaFile, qualifiedName: String) {
        val factory = JavaPsiFacade.getElementFactory(file.project)
        val importStatement = factory.createImportStatement(
            JavaPsiFacade.getInstance(file.project).findClass(qualifiedName, GlobalSearchScope.allScope(file.project))
                ?: return
        )

        // 중복 import 방지
        if (file.importList?.importStatements?.none { it.qualifiedName == qualifiedName } == true) {
            file.importList?.add(importStatement)
        }
    }

    private fun updateMethodCallExpression(methodCall: PsiMethodCallExpression, newQualifier: String) {
        val factory = JavaPsiFacade.getElementFactory(methodCall.project)
        val oldExpr = methodCall.methodExpression
        val methodName = oldExpr.referenceName ?: return

        val newExprText = "$newQualifier.$methodName"
        val newExpr = factory.createExpressionFromText(newExprText, methodCall)

        oldExpr.replace(newExpr)
    }

    private fun updateMethodCallWithSourceClassParam(
        methodCall: PsiMethodCallExpression,
        newQualifier: String,
        sourceClass: PsiClass,
        needsSourceClassParam: Boolean,
        isCalledFromSourceClass: Boolean
    ) {
        if (!needsSourceClassParam) {
            // 외부 의존성이 없는 경우 기존 방식으로 업데이트
            updateMethodCallExpression(methodCall, newQualifier)
            return
        }

        val factory = JavaPsiFacade.getElementFactory(methodCall.project)
        val oldExpr = methodCall.methodExpression
        val methodName = oldExpr.referenceName ?: return

        // 새 메소드 호출 표현식 생성
        val newExprText = "$newQualifier.$methodName"
        val newExpr = factory.createExpressionFromText(newExprText, methodCall)

        // 소스 클래스를 마지막 인자로 추가
        val argumentList = methodCall.argumentList
        val sourceClassRef = if (isCalledFromSourceClass) {
            "this" // 소스 클래스 내에서 호출하는 경우
        } else {
            sourceClass.name?.decapitalize() ?: "sourceClass" // 다른 클래스에서 호출하는 경우
        }

        val sourceClassArgExpr = factory.createExpressionFromText(sourceClassRef, methodCall)

        // 메소드 표현식 업데이트
        oldExpr.replace(newExpr)

        // 소스 클래스 인자 추가
        argumentList.add(sourceClassArgExpr)
    }

    // String extension function
    private fun String.decapitalize(): String {
        return if (isEmpty() || !first().isUpperCase()) this
        else first().lowercaseChar() + substring(1)
    }
}
