package org.example.emm // 패키지 선언

// 필요한 IntelliJ IDEA API 클래스들을 임포트
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType

// '메소드 이동' 리팩토링 액션 클래스
class MyRefactorAction : AnAction("Enhanced Move Method") {

    // 메소드 의존성 분석을 위한 의존성 주입
    private val dependencyAnalyzer = MethodDependencyAnalyzer()

    // 액션이 실행될 때 호출되는 메소드
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val currentMethod = findMethodAtCaret(e) ?: return
        val sourceClass = currentMethod.containingClass ?: return

        // 통합된 대화상자 표시
        val dialog = MoveMethodDialog(project, currentMethod.name, sourceClass.name ?: "")

        if (dialog.showAndGet()) {
            val targetClass = dialog.getSelectedClass()
            if (targetClass != null) {
                val accessModifier = dialog.getSelectedAccessModifier()
                handleElementChosen(e, targetClass, accessModifier)
            }
        }
    }

    private fun handleElementChosen(e: AnActionEvent, targetClass: PsiClass, accessModifier: String) {
        val project = e.project ?: return
        val currentMethod = findMethodAtCaret(e) ?: return
        val sourceClass = currentMethod.containingClass ?: return

        // 메소드와 그 의존성 메소드들의 이동 가능 여부 분석
        val methodGroupWithCanMoveMap = dependencyAnalyzer.findMethodGroupWithCanMove(currentMethod)
        val methodsCanMove = methodGroupWithCanMoveMap.filter { it.value }.keys.toSet() // 이동 가능한 메소드들
        val methodsCanNotMove = methodGroupWithCanMoveMap.filter { !it.value }.keys.toSet() // 이동 불가능한 외부 의존성 메소드들

        // 이동할 메소드들의 사용처 찾기

        // 메소드 이동 로직 실행
        val factory = JavaPsiFacade.getElementFactory(project) // PSI 요소 생성 팩토리
        val orderedMethodsToMove = sourceClass.methods.filter { it in methodsCanMove } // 원본 순서대로 메소드 정렬

        // 쓰기 작업 실행 (코드 수정)
        runWriteCommandAction(project) {
            // 모든 메소드를 대상 클래스에 복사
            for (methodToCopy in orderedMethodsToMove) {
                val methodCopy = factory.createMethodFromText(methodToCopy.text, targetClass)
                targetClass.add(methodCopy) // 대상 클래스에 메소드 추가
            }

            // 원본 메소드들을 역순으로 삭제 (의존성 문제 방지)
            for (methodToDelete in orderedMethodsToMove.reversed()) {
                methodToDelete.delete()
            }

            // 외부 의존성이 있는 경우 원본 클래스 임포트 추가
            if (methodsCanNotMove.isNotEmpty()) {
                val file = targetClass.containingFile as? PsiJavaFile
                if (file != null) {
                    addImportIfNeeded(file, sourceClass.qualifiedName ?: "")
                }
            }
        }

        // 타겟 메소드 및 타겟 메소드 내부 메소드를 호출하는 외부 메소드들의 참조 변경
        val callsToUpdate = collectMethodCalls(project, methodsCanMove)
        if (callsToUpdate.isNotEmpty()) {
            updateMethodCalls(
                project,
                callsToUpdate,
                targetClass,
                accessModifier  // 선택된 접근 제어자 전달
            )
        }

        // 완료 메시지 구성 및 표시
        val movedMethodsCount = methodsCanMove.size
        val externalDepsCount = methodsCanNotMove.size
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

    // 메소드 사용처 찾기
    private fun findUsages(project: Project, methods: Set<PsiMethod>): MutableList<PsiElement> {
        val usages = mutableListOf<PsiElement>() // 사용처 저장 리스트
        val searchScope = GlobalSearchScope.projectScope(project) // 프로젝트 전체 범위 지정

        // 각 메소드에 대해 참조 검색
        for (method in methods) {
            ReferencesSearch.search(method, searchScope).forEach { reference ->
                usages.add(reference.element) // 참조 요소 추가
            }
        }
        return usages
    }

    // 메소드 이동 확인 다이얼로그 표시
    private fun confirmMethodMoveFromDialog(
        project: Project,
        usages: List<PsiElement>,
        methodsToMove: Set<PsiMethod>,
        externalDependencies: Set<PsiMethod>
    ): Boolean {
        // 사용처 클래스 목록 추출
        val usageClasses = usages.mapNotNull {
            it.parentOfType<PsiClass>(false)?.qualifiedName
        }.distinct()

        // 이동할 메소드 목록 메시지 구성
        val movableMethodsMessage = if (methodsToMove.isNotEmpty()) {
            "\n\n이 메소드와 함께 다음 의존 메소드들이 이동됩니다:\n" +
                    methodsToMove.joinToString("\n") { it.name }
        } else ""

        // 이동되지 않는 메소드 목록 메시지 구성
        val externalDependenciesMessage = if (externalDependencies.isNotEmpty()) {
            "\n\n다음 메소드들은 다른 메소드에서도 사용되므로 이동되지 않습니다:\n" +
                    externalDependencies.joinToString("\n") { it.name } +
                    "\n(대신 소스 클래스 참조를 통해 호출됩니다)"
        } else ""

        // 사용처 메시지 구성
        val usagesMessage = if (usageClasses.isNotEmpty()) {
            "이 메소드는 다음 클래스에서 사용되고 있습니다:\n" +
                    usageClasses.joinToString("\n")
        } else ""

        // 전체 메시지 조합
        val message = listOf(usagesMessage, movableMethodsMessage, externalDependenciesMessage)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n") + "\n\n계속 진행하시겠습니까?"

        // 확인 다이얼로그 표시
        val result = Messages.showYesNoDialog(
            project,
            message,
            "메소드 이동 확인",
            Messages.getQuestionIcon()
        )

        return result == Messages.YES // 예/아니오 결과 반환
    }

    // 액션 활성화 조건 설정
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) // 현재 에디터 가져오기
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) // 현재 PSI 파일 가져오기
        val caret = editor?.caretModel?.currentCaret // 현재 캐럿 위치
        val offset = caret?.offset // 캐럿 오프셋
        val element = psiFile?.findElementAt(offset ?: -1) // 오프셋 위치의 PSI 요소
        var method = element?.parentOfType<PsiMethod>(true) // 요소의 부모 메소드 찾기
        e.presentation.isEnabledAndVisible = method != null // 메소드 내에 있을 때만 액션 활성화
    }

    // 현재 커서 위치의 메소드 찾기
    private fun findMethodAtCaret(e: AnActionEvent): PsiMethod? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null // 에디터 가져오기
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return null // PSI 파일 가져오기
        val offset = editor.caretModel.offset // 커서 위치
        val element = psiFile.findElementAt(offset) ?: return null // 해당 위치의 요소
        return element.parentOfType<PsiMethod>(true) // 요소의 부모 메소드 반환
    }

    // 메소드 호출 정보 저장 클래스
    private data class MethodCallInfo(
        val element: PsiElement, // 호출 요소
        val containingClass: PsiClass, // 호출하는 클래스
        val originalQualifier: String? // 원래 한정자 (예: this, obj 등)
    )

    // 메소드 호출 정보 수집
    private fun collectMethodCalls(
        project: Project,
        methodsCanMove: Set<PsiMethod>
    ): List<MethodCallInfo> {
        val usages = findUsages(project, methodsCanMove)
        val result = mutableListOf<MethodCallInfo>()

        // 각 사용처에 대해 호출 정보 수집
        for (usage in usages) {
            val methodCall = usage.parentOfType<PsiMethodCallExpression>(false) // 메소드 호출 식 찾기
            if (methodCall != null) {
                val containingClass = usage.parentOfType<PsiClass>(false) ?: continue // 호출하는 클래스

                // 현재 호출 형태 분석 (e.g., schoolRepository.deleteStudent(id))
                val qualifierExpression = methodCall.methodExpression.qualifierExpression
                val qualifier = qualifierExpression?.text // 한정자 추출

                result.add(MethodCallInfo(methodCall, containingClass, qualifier)) // 정보 추가
            }
        }

        return result
    }

    // 옮기려는 메소드를 호출하는 다른 메소드의 호출부 업데이트. 필요하면 import, 필드 추가 진행
    private fun updateMethodCalls(
        project: Project,
        callsToUpdate: List<MethodCallInfo>,
        targetClass: PsiClass,
        accessModifier: String  // 매개변수 추가){}){}
    ) {
        val targetClassName = targetClass.name ?: return
        val targetQualifiedName = targetClass.qualifiedName ?: return

        // 각 호출 업데이트
        for (call in callsToUpdate) {
            // 필드 인젝션 필요 여부 확인 (한정자 있고 대상 클래스 타입 필드 없음)
            val needsFieldInjection = call.originalQualifier != null &&
                    !hasFieldOfType(call.containingClass, targetQualifiedName)

            if (needsFieldInjection) {
                // 필드 추가가 필요한 경우 사용자에게 접근 제어자 선택 요청
                runWriteCommandAction(project) {
                    // 필드 추가
                    addFieldToClass(call.containingClass, targetClass, accessModifier)

                    // import 문 추가
                    addImportIfNeeded(call.containingClass.containingFile as PsiJavaFile, targetQualifiedName)

                    // 메소드 호출 업데이트
                    val variableName = targetClassName.decapitalize()
                    updateMethodCall(
                        call.element as PsiMethodCallExpression,
                        variableName
                        // 소스 클래스에서 호출하는지 여부
                    )
                }
            } else if (call.originalQualifier != null) {
                // 이미 필드가 있는 경우, 호출만 업데이트
                runWriteCommandAction(project) {
                    val variableName = targetClassName.decapitalize()
                    updateMethodCall(
                        call.element as PsiMethodCallExpression,
                        variableName
                    )
                }
            }
        }
    }

    // 특정 타입의 필드가 클래스에 있는지 확인
    private fun hasFieldOfType(containingClass: PsiClass, typeName: String): Boolean {
        return containingClass.fields.any { field ->
            field.type.canonicalText == typeName // 필드 타입 비교
        }
    }

    // 클래스에 필드 추가
    private fun addFieldToClass(
        containingClass: PsiClass,
        fieldType: PsiClass,
        accessModifier: String
    ) {
        val factory = JavaPsiFacade.getElementFactory(containingClass.project)
        val fieldName = fieldType.name?.decapitalize() ?: return
        val fieldText = "$accessModifier ${fieldType.name} $fieldName;" // 필드 선언문 생성
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

    // 필요한 import 문 추가
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

    private fun updateMethodCall(
        methodCall: PsiMethodCallExpression,
        newQualifier: String
    ) {
        val factory = JavaPsiFacade.getElementFactory(methodCall.project)
        val oldExpr = methodCall.methodExpression
        val methodName = oldExpr.referenceName ?: return

        // 새 메소드 호출 표현식 생성
        val newExprText = "$newQualifier.$methodName"
        val newExpr = factory.createExpressionFromText(newExprText, methodCall)

        // 메소드 표현식 업데이트
        oldExpr.replace(newExpr)
    }

    // 문자열의 첫 글자를 소문자로 변환하는 확장 함수
    private fun String.decapitalize(): String {
        return if (isEmpty() || !first().isUpperCase()) this
        else first().lowercaseChar() + substring(1)
    }
}