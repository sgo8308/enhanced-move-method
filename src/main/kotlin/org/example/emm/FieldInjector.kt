package org.example.emm

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope

/**
 * 필드 주입과 import 추가를 담당하는 클래스
 */
class FieldInjector(private val project: Project) {

    private val factory: PsiElementFactory = JavaPsiFacade.getElementFactory(project)

    /**
     * 클래스에 필요한 필드가 없는 경우 필드를 추가하고 import를 처리함
     */
    fun injectFieldIfNeeded(
        classToInjectField: PsiClass,
        fieldType: PsiClass,
        accessModifier: String
    ): Boolean {
        val targetQualifiedName = fieldType.qualifiedName ?: return false

        // 이미 필드가 있으면 추가하지 않음
        if (hasFieldOfType(classToInjectField, targetQualifiedName)) {
            return false
        }

        // 필드 추가
        addFieldToClass(classToInjectField, fieldType, accessModifier)

        // import 추가
        addImportIfNeeded(classToInjectField, targetQualifiedName)

        return true
    }

    /**
     * 특정 타입의 필드가 클래스에 있는지 확인
     */
    fun hasFieldOfType(dependentClass: PsiClass, typeName: String): Boolean {
        return dependentClass.fields.any { field ->
            field.type.canonicalText == typeName
        }
    }

    /**
     * 클래스에 필드 추가
     */
    private fun addFieldToClass(
        classToInjectField: PsiClass,
        fieldType: PsiClass,
        accessModifier: String
    ) {
        val fieldName = fieldType.name?.decapitalize() ?: return
        val fieldText = "$accessModifier ${fieldType.name} $fieldName;"
        val field = factory.createFieldFromText(fieldText, classToInjectField)

        val lastInstanceField = classToInjectField.fields.lastOrNull { !it.hasModifierProperty(PsiModifier.STATIC) }

        if (lastInstanceField != null) {
            classToInjectField.addAfter(field, lastInstanceField)
        } else {
            val anchor = classToInjectField.lBrace
            if (anchor != null) {
                classToInjectField.addAfter(field, anchor)
            } else {
                classToInjectField.add(field)
            }
        }
    }

    /**
     * 필요한 import 문 추가
     */
    fun addImportIfNeeded(targetClass: PsiClass, qualifiedName: String) {
        val file = targetClass.containingFile as? PsiJavaFile ?: return
        val importStatement = factory.createImportStatement(
            JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project))
                ?: return
        )

        // 중복 import 방지
        if (file.importList?.importStatements?.none { it.qualifiedName == qualifiedName } == true) {
            file.importList?.add(importStatement)
        }
    }

    // 문자열의 첫 글자를 소문자로 변환하는 확장 함수
    private fun String.decapitalize(): String {
        return if (isEmpty() || !first().isUpperCase()) this
        else first().lowercaseChar() + substring(1)
    }
}