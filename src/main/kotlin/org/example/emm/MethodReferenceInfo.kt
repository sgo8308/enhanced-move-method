package org.example.emm

import com.intellij.psi.*

/**
 * 메소드 참조와 관련된 정보를 저장하는 데이터 클래스
 */
data class MethodReferenceInfo(
    val element: PsiElement,
    val dependentClass: PsiClass,
    val originalQualifier: String?
)