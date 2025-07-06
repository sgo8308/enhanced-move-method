package org.example.emm

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.parentOfType

class MyRefactorAction : AnAction() {
    private lateinit var project: Project
    private val movableMethodFinder = MovableMethodFinder()

    override fun update(e: AnActionEvent) {
        e.presentation.text = "Enhanced Move Method"
        e.presentation.isEnabledAndVisible = isActionApplicable(e)
    }

    private fun isActionApplicable(e: AnActionEvent): Boolean {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val caret = editor?.caretModel?.currentCaret
        val offset = caret?.offset
        val element = psiFile?.findElementAt(offset ?: -1)
        return element?.parentOfType<PsiMethod>(true) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        project = e.project ?: return

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

        // MethodMover 클래스를 사용하여 메소드 이동 처리
        val methodMover = MethodMover(project)

        runWriteCommandAction(project) {
            methodMover.moveMethod(originalMethod, originalClass, targetClass, accessModifier)
        }

        // 완료 메시지 구성 및 표시
        showCompletionMessage(originalMethod, originalClass, targetClass, 
            movableMethodFinder.findMethodGroupWithCanMove(originalMethod)
                .filter { it.value }
                .keys.toSet()
        )
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
        movedMethods: Set<PsiMethod>
    ) {
        val movedMethodsMessage = buildString {
            append("메서드 '${originalMethod.name}'가 '${originalClass.name}'에서 '${targetClass.name}'로 이동되었습니다.")
            if (movedMethods.size > 1) {
                append("\n\n같이 이동된 내부 메서드:")
                movedMethods.filter { it != originalMethod }.forEach { method ->
                    append("\n- ${method.name}")
                }
            }
        }
        Messages.showMessageDialog(
            project,
            movedMethodsMessage,
            "메서드 이동 완료",
            Messages.getInformationIcon()
        )
    }
}