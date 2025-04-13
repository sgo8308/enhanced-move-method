package org.example.emm

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.util.parentOfType

class MyRefactorAction : AnAction("Enhanced Move Method") {
    // 프로젝트 인스턴스 필드 추가
    private lateinit var project: Project
    private lateinit var factory: PsiElementFactory

    // 의존성 분석기 및 참조 핸들러
    private val movableMethodFinder = MovableMethodFinder()
    private lateinit var methodReferenceFinder: MethodReferenceFinder
    private lateinit var methodReferenceUpdater: MethodReferenceUpdater
    private lateinit var fieldInjector: FieldInjector

    override fun actionPerformed(e: AnActionEvent) {
        project = e.project ?: return
        factory = JavaPsiFacade.getElementFactory(project)
        methodReferenceFinder = MethodReferenceFinder(project)
        methodReferenceUpdater = MethodReferenceUpdater(project)
        fieldInjector = FieldInjector(project)

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
        val methodGroupWithCanMoveMap = movableMethodFinder.findMethodGroupWithCanMove(originalMethod)
        val methodsToMove = methodGroupWithCanMoveMap.filter { it.value }.keys.toSet()
        val methodsToStay = methodGroupWithCanMoveMap.filter { !it.value }.keys.toSet()

        runWriteCommandAction(project) {
            // 이동 대상 메소드를 호출하는 외부 메소드들의 참조 변경
            val methodReferencesToUpdate = methodReferenceFinder.findReferencesOf(methodsToMove)
            for (reference in methodReferencesToUpdate) {
                if (reference.method in methodsToMove) {
                    continue
                }
                methodReferenceUpdater.updateMethodReferences(reference, targetClass, accessModifier)
            }

            // 이동 대상 메소드를 대상 클래스에 복사
            val orderedMethodsToMove = originalClass.methods.filter { it in methodsToMove }
            for (methodToCopy in orderedMethodsToMove) {
                val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)
                targetClass.add(methodCopy)
            }

            // 이동 대상 메소드들을 역순으로 삭제
            for (methodToDelete in orderedMethodsToMove.reversed()) {
                methodToDelete.delete()
            }
        }

        // 완료 메시지 구성 및 표시
        showCompletionMessage(originalMethod, originalClass, targetClass, methodsToMove.size, methodsToStay.size)
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

    private fun showCompletionMessage(
        originalMethod: PsiMethod,
        originalClass: PsiClass,
        targetClass: PsiClass,
        movedMethodsCount: Int,
        stayMethodsCount: Int
    ) {
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
}