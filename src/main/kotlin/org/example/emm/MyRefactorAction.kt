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

class MyRefactorAction : AnAction("Move Method to Another Class") {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caret = editor.caretModel.currentCaret
        val offset = caret.offset
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val element = psiFile.findElementAt(offset) ?: return

        // 클래스 이름 검색 팝업 창 표시
        val model = GotoClassModel2(project)
        val popup = ChooseByNamePopup.createPopup(project, model, null)
        popup.invoke(object : ChooseByNamePopupComponent.Callback() {
            override fun elementChosen(element: Any) {
                if (element is PsiClass) {
                    val targetClass = element

                    // 현재 선택된 메서드 찾기
                    val currentMethod = this@MyRefactorAction.findMethodAtCaret(e) ?: return
                    val sourceClass = currentMethod.containingClass ?: return
                    val methodName = currentMethod.name

                    // 메소드가 의존하는 다른 메소드들 찾기
                    val methodDependencies = findMethodDependencies(currentMethod, sourceClass)

                    // 모든 메소드 사용처 찾기 (현재 메소드 + 의존성 메소드들)
                    val allMethods = mutableListOf<PsiMethod>().apply {
                        add(currentMethod)
                        addAll(methodDependencies)
                    }

                    val usages = mutableListOf<PsiElement>()
                    val searchScope = GlobalSearchScope.projectScope(project)

                    // 모든 메소드의 사용처 찾기
                    for (method in allMethods) {
                        ReferencesSearch.search(method, searchScope)
                            .forEach { reference ->
                                usages.add(reference.element)
                            }
                    }

                    // 사용처가 있으면 사용자에게 알림
                    if (usages.isNotEmpty()) {
                        val usageClasses = usages.mapNotNull {
                            it.parentOfType<PsiClass>(false)?.qualifiedName
                        }.distinct()

                        val dependenciesMessage = if (methodDependencies.isNotEmpty()) {
                            "\n\n이 메소드와 함께 다음 의존 메소드들도 이동됩니다:\n" +
                                    methodDependencies.joinToString("\n") { it.name }
                        } else ""

                        val message = "이 메소드는 다음 클래스에서 사용되고 있습니다:\n" +
                                usageClasses.joinToString("\n") +
                                dependenciesMessage +
                                "\n\n계속 진행하시겠습니까?"

                        val result = Messages.showYesNoDialog(
                            project,
                            message,
                            "메소드 사용처 발견",
                            Messages.getQuestionIcon()
                        )

                        if (result != Messages.YES) {
                            return
                        }
                    }

                    // PsiElementFactory를 사용하여 메서드 복사본 생성
                    val factory = JavaPsiFacade.getElementFactory(project)

                    // 의존성 메소드들을 포함한 모든 메소드 복사 및 원본 삭제
                    runWriteCommandAction(project) {
                        // 소스 클래스에서 메소드 순서를 찾기
                        val orderedMethods = sourceClass.methods.filter { it in allMethods }

                        // 메소드를 원래 순서대로 대상 클래스에 추가
                        for (methodToCopy in orderedMethods) {
                            val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)
                            targetClass.add(methodCopy)
                        }

                        // 원본 메소드 삭제 (의존 관계가 있으므로 역순으로 삭제)
                        // 먼저 주 메소드를 삭제하고 나서 의존 메소드 삭제
                        for (methodToDelete in orderedMethods.reversed()) {
                            methodToDelete.delete()
                        }
                    }

                    // 메소드 사용처 업데이트
                    val callsToUpdate = collectMethodCalls(usages, allMethods.map { it.name }, sourceClass.name ?: "")

                    if (callsToUpdate.isNotEmpty()) {
                        updateMethodCalls(project, callsToUpdate, targetClass)
                    }

                    // 성공 메시지 표시
                    val movedMethodsCount = allMethods.size
                    val movedMethodsMessage = if (movedMethodsCount > 1) {
                        "메서드 '${methodName}' 및 관련된 ${movedMethodsCount - 1}개의 메서드가"
                    } else {
                        "메서드 '${methodName}'이(가)"
                    }

                    Messages.showMessageDialog(
                        project,
                        "${movedMethodsMessage} '${sourceClass.name}'에서 '${targetClass.name}'으로 이동되었습니다.",
                        "메서드 이동 완료",
                        Messages.getInformationIcon()
                    )
                }
            }
        }, ModalityState.current(), true)
    }

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

    private fun findMethodDependencies(
        method: PsiMethod,
        sourceClass: PsiClass
    ): Set<PsiMethod> {
        val dependencies = mutableSetOf<PsiMethod>()
        val processedMethods = mutableSetOf<PsiMethod>()

        fun processMethod(currentMethod: PsiMethod) {
            if (currentMethod in processedMethods) return
            processedMethods.add(currentMethod)

            // 메소드 내에서 호출하는 다른 메소드들 찾기
            val methodCalls = PsiTreeUtil.findChildrenOfType(currentMethod, PsiMethodCallExpression::class.java)

            for (methodCall in methodCalls) {
                val resolveResult = methodCall.methodExpression.advancedResolve(false)
                val calledMethod = resolveResult.element as? PsiMethod ?: continue
                val calledMethodClass = calledMethod.containingClass ?: continue

                // 같은 클래스 내의 메소드만 처리
                if (calledMethodClass == sourceClass && calledMethod != method && calledMethod !in dependencies) {
                    dependencies.add(calledMethod)
                    processMethod(calledMethod) // 재귀적으로 의존성 분석
                }
            }
        }

        processMethod(method)
        return dependencies
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
        targetClass: PsiClass
    ) {
        val targetClassName = targetClass.name ?: return
        val targetQualifiedName = targetClass.qualifiedName ?: return

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

                        // 메소드 호출 업데이트 (예: schoolRepository -> studentRepository)
                        val variableName = targetClassName.decapitalize()
                        updateMethodCallExpression(call.element as PsiMethodCallExpression, variableName)
                    }
                }
            } else if (call.originalQualifier != null) {
                // 이미 필드가 있는 경우, 호출만 업데이트
                runWriteCommandAction(project) {
                    val variableName = targetClassName.decapitalize()
                    updateMethodCallExpression(call.element as PsiMethodCallExpression, variableName)
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

    // String extension function
    private fun String.decapitalize(): String {
        return if (isEmpty() || !first().isUpperCase()) this
        else first().lowercaseChar() + substring(1)
    }
}