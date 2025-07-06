package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * 메소드 이동 관련 작업을 담당하는 클래스
 */
class MethodMover(private val project: Project) {
    private val factory: PsiElementFactory = JavaPsiFacade.getElementFactory(project)
    private val methodReferenceFinder = MethodReferenceFinder(project)
    private val methodReferenceUpdater = MethodReferenceUpdater(project)
    private val fieldInjector = FieldInjector(project)
    private val movableMethodFinder = MovableMethodFinder()

    /**
     * 메소드 이동을 수행하는 메인 메소드
     *
     * @return 이동된 메서드 목록
     */
    fun moveMethod(
        originalMethod: PsiMethod,
        originalClass: PsiClass,
        targetClass: PsiClass,
        accessModifier: String
    ): Set<PsiMethod> {
        // 메소드와 그 의존성 메소드들의 이동 가능 여부 분석
        val methodGroupWithCanMoveMap = movableMethodFinder.findMethodGroupWithCanMove(originalMethod)
        val methodsToMove = methodGroupWithCanMoveMap.filter { it.value }.keys.toSet()
        val methodsToStay = methodGroupWithCanMoveMap.filter { !it.value }.keys.toSet()

        // 이동 대상 메소드를 호출하는 외부 메소드들의 참조 변경
        val methodReferencesToUpdate = updateExternalReferences(methodsToMove, targetClass, accessModifier)

        // 이동하지 않는 메소드를 참조하는 이동하는 메소드들의 참조 변경
        updateInternalReferences(methodsToStay, methodsToMove, originalClass, targetClass, accessModifier)

        // 외부 클래스 의존성 처리
        handleExternalDependencies(methodsToMove, originalClass, targetClass, accessModifier)

        // 메소드 복사 및 접근 제어자 설정
        copyMethodsToTargetClass(methodsToMove, originalMethod, originalClass, targetClass, methodReferencesToUpdate)

        // 원본 메소드 삭제 및 정리 작업
        cleanupAfterMove(methodsToMove, originalClass, targetClass, methodReferencesToUpdate)

        // 이동된 메서드 목록 반환
        return methodsToMove
    }

    /**
     * 이동 대상 메소드를 호출하는 외부 메소드들의 참조 변경
     */
    private fun updateExternalReferences(
        methodsToMove: Set<PsiMethod>,
        targetClass: PsiClass,
        accessModifier: String
    ): List<MethodReferenceInfo> {
        val methodReferencesToUpdate = methodReferenceFinder.findReferencesOf(methodsToMove)
        for (reference in methodReferencesToUpdate) {
            if (reference.method in methodsToMove) {
                continue
            }
            methodReferenceUpdater.updateForMethodsNotInMethodGroup(reference, targetClass, accessModifier)
        }
        return methodReferencesToUpdate
    }

    /**
     * 이동하지 않는 메소드를 참조하는 이동하는 메소드들의 참조 변경
     */
    private fun updateInternalReferences(
        methodsToStay: Set<PsiMethod>,
        methodsToMove: Set<PsiMethod>,
        originalClass: PsiClass,
        targetClass: PsiClass,
        accessModifier: String
    ) {
        val methodReferences = methodReferenceFinder.findReferencesOf(methodsToStay)
        for (reference in methodReferences) {
            if (reference.method in methodsToMove) {
                methodReferenceUpdater.updateForMethodsInMethodGroup(
                    reference.element,
                    originalClass,
                    targetClass,
                    accessModifier
                )
            }
        }
    }

    /**
     * 외부 클래스 의존성 처리
     */
    private fun handleExternalDependencies(
        methodsToMove: Set<PsiMethod>,
        originalClass: PsiClass,
        targetClass: PsiClass,
        accessModifier: String
    ) {
        methodReferenceFinder.findExternalClassesReferencedInMethods(methodsToMove, originalClass, targetClass)
            .forEach { externalClass ->
                fieldInjector.injectFieldIfNeeded(targetClass, externalClass, accessModifier)
            }

        // 이동 대상 메소드에 필요한 import 추가
        val usedClasses = methodReferenceFinder.collectUsedClasses(methodsToMove.first())
        addImportsToTargetClass(targetClass, usedClasses)
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

    /**
     * 메소드 복사 및 접근 제어자 설정
     */
    private fun copyMethodsToTargetClass(
        methodsToMove: Set<PsiMethod>,
        originalMethod: PsiMethod,
        originalClass: PsiClass,
        targetClass: PsiClass,
        methodReferencesToUpdate: List<MethodReferenceInfo>
    ) {
        val orderedMethodsToMove = originalClass.methods.filter { it in methodsToMove }

        // 외부에서 참조되는 메서드 확인
        val methodsReferencedExternally = findMethodsReferencedExternally(methodsToMove, methodReferencesToUpdate)

        for (methodToCopy in orderedMethodsToMove) {
            val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)

            // 외부에서 참조되는 메서드는 public으로 설정
            if (methodToCopy in methodsReferencedExternally) {
                methodReferenceUpdater.changeMethodModifier(methodCopy, PsiModifier.PUBLIC)
            } else if (methodToCopy != originalMethod) {
                methodReferenceUpdater.changeMethodModifier(methodCopy, PsiModifier.PRIVATE)
            }

            targetClass.add(methodCopy)
        }
    }

    /**
     * 외부에서 참조되는 메서드 찾기
     */
    private fun findMethodsReferencedExternally(
        methodsToMove: Set<PsiMethod>,
        methodReferencesToUpdate: List<MethodReferenceInfo>
    ): Set<PsiMethod> {
        val methodsReferencedExternally = mutableSetOf<PsiMethod>()

        for (reference in methodReferencesToUpdate) {
            // 이동할 메서드 중에서 참조 표현식의 메서드 이름과 일치하는 메서드 찾기
            val methodName = reference.element.methodExpression.referenceName ?: continue
            val matchedMethod = methodsToMove.find { it.name == methodName }

            if (matchedMethod != null) {
                // 참조하는 메서드가 이동하지 않는 메서드인 경우(외부 참조)
                if (reference.method !in methodsToMove) {
                    methodsReferencedExternally.add(matchedMethod)
                }
            }
        }

        return methodsReferencedExternally
    }

    /**
     * 원본 메소드 삭제 및 정리 작업
     */
    private fun cleanupAfterMove(
        methodsToMove: Set<PsiMethod>,
        originalClass: PsiClass,
        targetClass: PsiClass,
        methodReferencesToUpdate: List<MethodReferenceInfo>
    ) {
        // 이동 대상 메소드들을 역순으로 삭제
        val orderedMethodsToMove = originalClass.methods.filter { it in methodsToMove }
        for (methodToDelete in orderedMethodsToMove.reversed()) {
            methodToDelete.delete()
        }

        // 불필요한 필드 제거
        cleanupUnusedFields(methodReferencesToUpdate, originalClass, targetClass)

        // 임포트 최적화
        optimizeImports(originalClass)
        optimizeImports(targetClass)
    }

    /**
     * 불필요한 필드 제거
     */
    private fun cleanupUnusedFields(
        methodReferencesToUpdate: List<MethodReferenceInfo>,
        originalClass: PsiClass,
        targetClass: PsiClass
    ) {
        val dirtyClasses = methodReferencesToUpdate.map { it.containingClass } + originalClass + targetClass
        for (psiClass in dirtyClasses) {
            val fields = psiClass.fields
            for (field in fields) {
                if (isSafeToDelete(field)) {
                    field.delete()
                }
            }
        }
    }

    /**
     * 필드 삭제 가능 여부 확인
     */
    private fun isSafeToDelete(field: PsiField): Boolean {
        val searchScope = GlobalSearchScope.projectScope(project)
        val references = ReferencesSearch.search(field, searchScope).findAll()
        return references.isEmpty()
    }

    /**
     * 타겟 클래스의 import 최적화
     */
    private fun optimizeImports(targetClass: PsiClass) {
        val file = targetClass.containingFile as? PsiJavaFile ?: return
        JavaCodeStyleManager.getInstance(project).optimizeImports(file)
    }
}
