package org.example.emm

import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*

class MoveMethodDialog(
    private val project: Project,
    private val initialMethodName: String,
    private val sourceClassName: String
) : DialogWrapper(project) {

    private var selectedClass: PsiClass? = null
    private val targetClassField = JBTextField()
    private val accessModifiers = arrayOf("private final", "private", "public final", "public")
    private val radioButtons = accessModifiers.map { JBRadioButton(it) }.toTypedArray()

    init {
        title = "메소드 이동 - $initialMethodName"
        init()
        radioButtons[0].isSelected = true  // 기본값 설정
    }

    // 대화상자가 표시되기 전에 클래스 선택기를 먼저 표시
    override fun show() {
        if (openClassChooser()) {
            super.show()
        } else {
            close(CANCEL_EXIT_CODE)
        }
    }

    // 대화상자의 기본 크기 설정
    override fun getPreferredSize(): Dimension {
        return Dimension(500, 250)  // 가로 500px, 세로 250px
    }

    fun getSelectedClass(): PsiClass? = selectedClass

    fun getSelectedAccessModifier(): String {
        return radioButtons.first { it.isSelected }.text
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))

        // 상단 정보 패널
        val infoPanel = JPanel(GridLayout(2, 1, 0, 5))
        infoPanel.add(JBLabel("메소드: $initialMethodName"))
        infoPanel.add(JBLabel("원본 클래스: $sourceClassName"))
        panel.add(infoPanel, BorderLayout.NORTH)

        // 중앙 클래스 선택 패널
        val classPanel = JPanel(BorderLayout(5, 0))
        classPanel.add(JBLabel("대상 클래스: "), BorderLayout.WEST)

        val classSelectorPanel = JPanel(BorderLayout(5, 0))
        classSelectorPanel.add(targetClassField, BorderLayout.CENTER)
        targetClassField.preferredSize = Dimension(300, targetClassField.preferredSize.height)  // 텍스트 필드 가로 크기 조정

        val browseButton = JButton("변경...")
        browseButton.addActionListener { openClassChooser() }
        classSelectorPanel.add(browseButton, BorderLayout.EAST)

        classPanel.add(classSelectorPanel, BorderLayout.CENTER)
        panel.add(classPanel, BorderLayout.CENTER)

        // 하단 접근 제어자 선택 패널
        val accessModifierPanel = JPanel(BorderLayout(0, 5))
        accessModifierPanel.add(JBLabel("필드 접근 제어자:"), BorderLayout.NORTH)

        val radioPanel = JPanel(GridLayout(radioButtons.size, 1))
        val buttonGroup = ButtonGroup()

        radioButtons.forEach { radioButton ->
            buttonGroup.add(radioButton)
            radioPanel.add(radioButton)
        }

        accessModifierPanel.add(radioPanel, BorderLayout.CENTER)
        panel.add(accessModifierPanel, BorderLayout.SOUTH)

        return panel
    }

    // 클래스 선택창 열기 - 선택 여부를 boolean으로 반환
    private fun openClassChooser(): Boolean {
        val chooserFactory = TreeClassChooserFactory.getInstance(project)
        val chooser = chooserFactory.createProjectScopeChooser("대상 클래스 선택")

        chooser.showDialog()
        val selected = chooser.selected

        if (selected != null) {
            selectedClass = selected
            targetClassField.text = selected.qualifiedName ?: selected.name ?: ""
            return true
        }
        return false
    }
}