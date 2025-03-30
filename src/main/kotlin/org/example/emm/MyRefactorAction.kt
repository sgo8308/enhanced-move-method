package org.example.emm

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiMethod

class MyRefactorAction : AnAction("Move Method to Another Class") {
    override fun actionPerformed(e: AnActionEvent) {
        println("hello world")
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caret = editor.caretModel.currentCaret
        val offset = caret.offset
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val element = psiFile.findElementAt(offset) ?: return

        // ...existing code to find the method and target class...

        Messages.showMessageDialog(project, "Method moved successfully!", "Info", Messages.getInformationIcon())
    }

//    override fun update(e: AnActionEvent) {
//        println("inside update")
//        val editor = e.getData(CommonDataKeys.EDITOR)
//        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
//        val caret = editor?.caretModel?.currentCaret
//        val offset = caret?.offset
//        val element = psiFile?.findElementAt(offset ?: return)
//        println(element)
//
//        // Check if the caret is inside a method
//        val isInsideMethod = element?.parent?.parent is PsiMethod
//        println(isInsideMethod)
//        e.presentation.isEnabledAndVisible = isInsideMethod
//    }
}
