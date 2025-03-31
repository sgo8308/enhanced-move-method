package org.example.emm

import com.intellij.ide.util.gotoByName.ChooseByNamePopup
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.parentOfType
import com.intellij.ide.util.gotoByName.ChooseByNamePopupComponent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction.*
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass

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

                    // PsiElementFactory를 사용하여 메서드 복사본 생성
                    val factory = JavaPsiFacade.getElementFactory(project)
                    val methodCopy = factory.createMethodFromText(currentMethod.text, targetClass)

                    // 메서드를 대상 클래스에 추가
                    runWriteCommandAction(project) {
                        // 새 메서드를 대상 클래스에 추가
                        targetClass.add(methodCopy)

                        // 원본 메서드 삭제
                        currentMethod.delete()

                        // 성공 메시지 표시
                        Messages.showMessageDialog(
                            project,
                            "메서드 '${currentMethod.name}'이(가) '${sourceClass.name}'에서 '${targetClass.name}'으로 이동되었습니다.",
                            "메서드 이동 완료",
                            Messages.getInformationIcon()
                        )
                    }
                }
            }
        }, ModalityState.current(), true)

        Messages.showMessageDialog(project, "Method moved successfully!", "Info", Messages.getInformationIcon())
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
}
