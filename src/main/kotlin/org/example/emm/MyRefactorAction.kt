package org.example.emm

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.parentOfType

class MyRefactorAction : AnAction() {
    private lateinit var project: Project
    private lateinit var factory: PsiElementFactory

    private val movableMethodFinder = MovableMethodFinder()
    private lateinit var methodReferenceFinder: MethodReferenceFinder
    private lateinit var methodReferenceUpdater: MethodReferenceUpdater
    private lateinit var fieldInjector: FieldInjector

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
                methodReferenceUpdater.updateForMethodsNotInMethodGroup(reference, targetClass, accessModifier)
            }

            // 이동하지 않는 메소드를 참조하는 이동하는 메소드들의 참조 변경
            val methodReferences = methodReferenceFinder.findReferencesOf(methodsToStay)
            for (reference in methodReferences) {
                if (reference.method in methodsToMove) {
                    methodReferenceUpdater.updateForMethodsInMethodGroup(
                        reference.element,
                        originalClass,
                        targetClass,
                        accessModifier,
                    )
                }
            }

            //이동 대상 메소드 내부에서 참조하는 외부 클래스들에 대해 필요할 경우 필드 주입
            methodReferenceFinder.findExternalClassesReferencedInMethods(methodsToMove, originalClass, targetClass)
                .forEach { externalClass ->
                    fieldInjector.injectFieldIfNeeded(targetClass, externalClass, accessModifier)
                }

            // 이동 대상 메소드에 필요한 import 추가
            val usedClasses = collectUsedClasses(originalMethod)
            addImportsToTargetClass(targetClass, usedClasses)

            // 이동 대상 메소드를 대상 클래스에 복사
            val orderedMethodsToMove = originalClass.methods.filter { it in methodsToMove }
            val externalDependentMethods = methodReferencesToUpdate.map { it.method }
            for (methodToCopy in orderedMethodsToMove) {
                val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)
                if (methodToCopy !in externalDependentMethods && methodToCopy != originalMethod) {
                    methodReferenceUpdater.changeMethodModifier(methodCopy, PsiModifier.PRIVATE)
                }

                targetClass.add(methodCopy)
            }

            // 이동 대상 메소드들을 역순으로 삭제
            for (methodToDelete in orderedMethodsToMove.reversed()) {
                methodToDelete.delete()
            }

            // 메소드 이동과 관련된 모든 클래스에 대해서 safe delete 수행
            val dirtyClasses = methodReferencesToUpdate.map { it.containingClass } + originalClass + targetClass
            for (psiClass in dirtyClasses) {
                val fields = psiClass.fields
                for (field in fields) {
                    if (isSafeToDelete(field, project)) {
                        field.delete()
                    }
                }
            }
        }

        // 완료 메시지 구성 및 표시
        showCompletionMessage(originalMethod, originalClass, targetClass, methodsToMove)
    }


    private fun isSafeToDelete(field: PsiField, project: Project): Boolean {
        val searchScope = GlobalSearchScope.projectScope(project)
        val references = ReferencesSearch.search(field, searchScope).findAll()
        return references.isEmpty()
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

    /**
     * 메소드에서 사용된 클래스 수집
     */
    private fun collectUsedClasses(method: PsiMethod): Set<PsiClass> {
        val usedClasses = mutableSetOf<PsiClass>()

        // 반환 타입
        method.returnType?.let { returnType ->
            if (returnType is PsiClassType) {
                PsiUtil.resolveClassInType(returnType)?.let { usedClasses.add(it) }
                returnType.parameters.mapNotNull { parameterType ->
                    PsiUtil.resolveClassInType(parameterType)
                }.forEach { usedClasses.add(it) }
            }
        }

        // 매개변수 타입
        method.parameterList.parameters.forEach { param ->
            val paramType = param.type
            if (paramType is PsiClassType) {
                PsiUtil.resolveClassInType(paramType)?.let { usedClasses.add(it) }
                paramType.parameters.mapNotNull { parameterType ->
                    PsiUtil.resolveClassInType(parameterType)
                }.forEach { usedClasses.add(it) }
            }
        }
        // throws 예외 타입
        method.throwsList.referencedTypes.forEach { type ->
            type.resolve()?.let { usedClasses.add(it) }
        }

        // 메소드 내부에서 사용된 클래스
        method.body?.let { body ->
            body.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceExpression(expression: PsiReferenceExpression) {
                    super.visitReferenceExpression(expression)
                    expression.resolve()?.let { resolved ->
                        if (resolved is PsiClass) {
                            usedClasses.add(resolved)
                        }
                    }
                }

                override fun visitTypeElement(typeElement: PsiTypeElement) {
                    super.visitTypeElement(typeElement)
                    PsiUtil.resolveClassInType(typeElement.type)?.let { usedClasses.add(it) }
                }

                override fun visitNewExpression(expression: PsiNewExpression) {
                    super.visitNewExpression(expression)
                    expression.classReference?.resolve()?.let { resolved ->
                        if (resolved is PsiClass) {
                            usedClasses.add(resolved)
                        }
                    }
                }
            })
        }

        return usedClasses
    }

    /**
     * 타겟 클래스에 필요한 import 추가
     */
    private fun addImportsToTargetClass(targetClass: PsiClass, usedClasses: Set<PsiClass>) {
        val file = targetClass.containingFile as? PsiJavaFile ?: return

        usedClasses.forEach { psiClass ->
            val qualifiedName = psiClass.qualifiedName ?: return@forEach
            val importStatement = factory.createImportStatement(
                JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
                    ?: return@forEach
            )

            // 중복 import 방지
            if (file.importList?.importStatements?.none { it.qualifiedName == qualifiedName } == true) {
                file.importList?.add(importStatement)
            }
        }
    }
}