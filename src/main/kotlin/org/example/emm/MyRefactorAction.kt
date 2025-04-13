package org.example.emm

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType

class MyRefactorAction : AnAction("Enhanced Move Method") {
    // 프로젝트 인스턴스 필드 추가
    private lateinit var project: Project
    private lateinit var factory: PsiElementFactory

    // 메소드 의존성 분석을 위한 의존성 주입
    private val dependencyAnalyzer = MethodDependencyAnalyzer()

    override fun actionPerformed(e: AnActionEvent) {
        project = e.project ?: return
        factory = JavaPsiFacade.getElementFactory(project)

        val originalMethod = findMethodAtCaret(e) ?: return
        val originalClass = originalMethod.containingClass ?: return

        // 통합된 대화상자 표시
        val dialog = MoveMethodDialog(project, originalMethod.name, originalClass.name ?: "")

        if (dialog.showAndGet()) {
            val targetClass = dialog.getSelectedClass()
            if (targetClass != null) {
                val accessModifier = dialog.getSelectedAccessModifier()
                handleElementChosen(e, targetClass, accessModifier)
            }
        }
    }

    private fun handleElementChosen(e: AnActionEvent, targetClass: PsiClass, accessModifier: String) {
        val originalMethod = findMethodAtCaret(e) ?: return
        val originalClass = originalMethod.containingClass ?: return

        // 메소드와 그 의존성 메소드들의 이동 가능 여부 분석
        val methodGroupWithCanMoveMap = dependencyAnalyzer.findMethodGroupWithCanMove(originalMethod)
        val methodsToMove = methodGroupWithCanMoveMap.filter { it.value }.keys.toSet()
        val methodsToStay = methodGroupWithCanMoveMap.filter { !it.value }.keys.toSet()

        // 메소드 이동 로직 실행
        val orderedMethodsToMove = originalClass.methods.filter { it in methodsToMove }

        // 쓰기 작업 실행 (코드 수정)
        runWriteCommandAction(project) {
            // 모든 메소드를 대상 클래스에 복사
            for (methodToCopy in orderedMethodsToMove) {
                val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)
                targetClass.add(methodCopy)
            }

            // 원본 메소드들을 역순으로 삭제
            for (methodToDelete in orderedMethodsToMove.reversed()) {
                methodToDelete.delete()
            }

            // 외부 의존성이 있는 경우 원본 클래스 임포트 추가
            if (methodsToStay.isNotEmpty()) {
                val file = targetClass.containingFile as? PsiJavaFile
                if (file != null) {
                    addImportIfNeeded(file, originalClass.qualifiedName ?: "")
                }
            }
        }

        // 타겟 메소드 및 타겟 메소드 내부 메소드를 호출하는 외부 메소드들의 참조 변경
        val methodReferencesToUpdate = collectMethodReferences(methodsToMove)
        if (methodReferencesToUpdate.isNotEmpty()) {
            updateMethodReferences(
                methodReferencesToUpdate,
                targetClass,
                accessModifier
            )
        }

        // 완료 메시지 구성 및 표시
        val movedMethodsCount = methodsToMove.size
        val stayMethodsCount = methodsToStay.size
        val movedMethodsMessage = if (movedMethodsCount > 1) {
            "메서드 '${originalMethod.name}' 및 관련된 ${movedMethodsCount - 1}개의 메서드가"
        } else {
            "메서드 '${originalMethod.name}'이(가)"
        }
        val stayMethodsMessage = if (stayMethodsCount > 0) {
            "\n\n${stayMethodsCount}개의 메서드는 다른 메서드에서도 사용되므로 이동되지 않았으며, 대신 소스 클래스를 통해 호출됩니다."
        } else ""
        Messages.showMessageDialog(
            project,
            "${movedMethodsMessage} '${originalClass.name}'에서 '${targetClass.name}'으로 이동되었습니다.${stayMethodsMessage}",
            "메서드 이동 완료",
            Messages.getInformationIcon()
        )
    }

    // 메소드 사용처 찾기
    private fun findMethodUsages(methods: Set<PsiMethod>): MutableList<PsiElement> {
        val usages = mutableListOf<PsiElement>()
        val searchScope = GlobalSearchScope.projectScope(project)

        for (method in methods) {
            ReferencesSearch.search(method, searchScope).forEach { reference ->
                usages.add(reference.element)
            }
        }
        return usages
    }

    // 메소드 이동 확인 다이얼로그 표시
    private fun confirmMethodMoveFromDialog(
        usages: List<PsiElement>,
        methodsToMove: Set<PsiMethod>,
        methodsToStay: Set<PsiMethod>
    ): Boolean {
        // 사용처 클래스 목록 추출
        val dependentClasses = usages.mapNotNull {
            it.parentOfType<PsiClass>(false)?.qualifiedName
        }.distinct()

        // 이동할 메소드 목록 메시지 구성
        val movableMethodsMessage = if (methodsToMove.isNotEmpty()) {
            "\n\n이 메소드와 함께 다음 의존 메소드들이 이동됩니다:\n" +
                    methodsToMove.joinToString("\n") { it.name }
        } else ""

        // 이동되지 않는 메소드 목록 메시지 구성
        val stayMethodsMessage = if (methodsToStay.isNotEmpty()) {
            "\n\n다음 메소드들은 다른 메소드에서도 사용되므로 이동되지 않습니다:\n" +
                    methodsToStay.joinToString("\n") { it.name } +
                    "\n(대신 소스 클래스 참조를 통해 호출됩니다)"
        } else ""

        // 사용처 메시지 구성
        val usagesMessage = if (dependentClasses.isNotEmpty()) {
            "이 메소드는 다음 클래스에서 사용되고 있습니다:\n" +
                    dependentClasses.joinToString("\n")
        } else ""

        // 전체 메시지 조합
        val message = listOf(usagesMessage, movableMethodsMessage, stayMethodsMessage)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n") + "\n\n계속 진행하시겠습니까?"

        // 확인 다이얼로그 표시
        val result = Messages.showYesNoDialog(
            project,
            message,
            "메소드 이동 확인",
            Messages.getQuestionIcon()
        )

        return result == Messages.YES
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val caret = editor?.caretModel?.currentCaret
        val offset = caret?.offset
        val element = psiFile?.findElementAt(offset ?: -1)
        val method = element?.parentOfType<PsiMethod>(true)
        e.presentation.isEnabledAndVisible = method != null
    }

    private fun findMethodAtCaret(e: AnActionEvent): PsiMethod? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return null
        return element.parentOfType<PsiMethod>(true)
    }

    // 메소드 호출 정보 저장 클래스
    private data class MethodReferenceInfo(
        val element: PsiElement,
        val dependentClass: PsiClass,
        val originalQualifier: String?
    )

    // 메소드 호출 정보 수집
    private fun collectMethodReferences(
        methodsToMove: Set<PsiMethod>
    ): List<MethodReferenceInfo> {
        val usages = findMethodUsages(methodsToMove)
        val result = mutableListOf<MethodReferenceInfo>()

        for (usage in usages) {
            val methodCall = usage.parentOfType<PsiMethodCallExpression>(false)
            if (methodCall != null) {
                val dependentClass = usage.parentOfType<PsiClass>(false) ?: continue

                val qualifierExpression = methodCall.methodExpression.qualifierExpression
                val qualifier = qualifierExpression?.text

                result.add(MethodReferenceInfo(methodCall, dependentClass, qualifier))
            }
        }

        return result
    }

    // 메소드 호출 업데이트
    private fun updateMethodReferences(
        methodReferencesToUpdate: List<MethodReferenceInfo>,
        targetClass: PsiClass,
        accessModifier: String
    ) {
        val targetClassName = targetClass.name ?: return
        val targetQualifiedName = targetClass.qualifiedName ?: return

        for (reference in methodReferencesToUpdate) {
            updateMethodQualifier(reference, targetQualifiedName, targetClass, accessModifier, targetClassName)
        }
    }

    private fun updateMethodQualifier(
        reference: MethodReferenceInfo,
        targetQualifiedName: @NlsSafe String,
        targetClass: PsiClass,
        accessModifier: String,
        targetClassName: @NlsSafe String
    ) {
        val needsFieldInjection = reference.originalQualifier != null &&
                !hasFieldOfType(reference.dependentClass, targetQualifiedName)

        if (needsFieldInjection) {
            runWriteCommandAction(project) {
                // 필드 추가
                addFieldToClass(reference.dependentClass, targetClass, accessModifier)

                // import 문 추가
                addImportIfNeeded(reference.dependentClass.containingFile as PsiJavaFile, targetQualifiedName)

                // 메소드 호출 업데이트
                val variableName = targetClassName.decapitalize()
                updateMethodReference(
                    reference.element as PsiMethodCallExpression,
                    variableName
                )
            }
        } else if (reference.originalQualifier != null) {
            runWriteCommandAction(project) {
                val variableName = targetClassName.decapitalize()
                updateMethodReference(
                    reference.element as PsiMethodCallExpression,
                    variableName
                )
            }
        }
    }

    // 특정 타입의 필드가 클래스에 있는지 확인
    private fun hasFieldOfType(dependentClass: PsiClass, typeName: String): Boolean {
        return dependentClass.fields.any { field ->
            field.type.canonicalText == typeName
        }
    }

    // 클래스에 필드 추가
    private fun addFieldToClass(
        dependentClass: PsiClass,
        targetClass: PsiClass,
        accessModifier: String
    ) {
        val fieldName = targetClass.name?.decapitalize() ?: return
        val fieldText = "$accessModifier ${targetClass.name} $fieldName;"
        val field = factory.createFieldFromText(fieldText, dependentClass)

        val lastInstanceField = dependentClass.fields.lastOrNull { !it.hasModifierProperty(PsiModifier.STATIC) }

        if (lastInstanceField != null) {
            dependentClass.addAfter(field, lastInstanceField)
        } else {
            val anchor = dependentClass.lBrace
            if (anchor != null) {
                dependentClass.addAfter(field, anchor)
            } else {
                dependentClass.add(field)
            }
        }
    }

    // 필요한 import 문 추가
    private fun addImportIfNeeded(file: PsiJavaFile, qualifiedName: String) {
        val importStatement = factory.createImportStatement(
            JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
                ?: return
        )

        // 중복 import 방지
        if (file.importList?.importStatements?.none { it.qualifiedName == qualifiedName } == true) {
            file.importList?.add(importStatement)
        }
    }

    private fun updateMethodReference(
        methodReference: PsiMethodCallExpression,
        newQualifier: String
    ) {
        val oldExpr = methodReference.methodExpression
        val methodName = oldExpr.referenceName ?: return

        // 새 메소드 호출 표현식 생성
        val newExprText = "$newQualifier.$methodName"
        val newExpr = factory.createExpressionFromText(newExprText, methodReference)

        // 메소드 표현식 업데이트
        oldExpr.replace(newExpr)
    }

    // 문자열의 첫 글자를 소문자로 변환하는 확장 함수
    private fun String.decapitalize(): String {
        return if (isEmpty() || !first().isUpperCase()) this
        else first().lowercaseChar() + substring(1)
    }
}