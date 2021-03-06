package org.jetbrains.plugins.scala
package editor.smartEnter.fixers

import com.intellij.psi._
import com.intellij.openapi.editor.{Editor, Document}
import editor.smartEnter.ScalaSmartEnterProcessor
import util.PsiTreeUtil
import lang.psi.api.expr.{ScBlockExpr, ScExpression, ScIfStmt}

/**
 * @author Ksenia.Sautina
 * @since 1/31/13
 */

class ScalaMissingIfBranchesFixer extends ScalaFixer {
  def apply(editor: Editor, processor: ScalaSmartEnterProcessor, psiElement: PsiElement) {
    val ifStatement = PsiTreeUtil.getParentOfType(psiElement, classOf[ScIfStmt], false)
    if (ifStatement != null) {
      val doc: Document = editor.getDocument
      val elseBranch: ScExpression = ifStatement.elseBranch.getOrElse(null)
      val thenBranch: ScExpression = ifStatement.thenBranch.getOrElse(null)
      if (thenBranch.isInstanceOf[ScBlockExpr]) return
      var transformingOneLiner: Boolean = false
      if (thenBranch != null && startLine(doc, thenBranch) == startLine(doc, ifStatement)) {
        if (ifStatement.condition.getOrElse(null) != null) {
          return
        }
        transformingOneLiner = true
      }
      val rParenth = ifStatement.getRightParenthesis.getOrElse(null)
      assert(rParenth != null)
      if (elseBranch == null && !transformingOneLiner || thenBranch == null) {
        doc.insertString(rParenth.getTextRange.getEndOffset, "{}")
      }
      else {
        doc.insertString(rParenth.getTextRange.getEndOffset, "{")
        doc.insertString(thenBranch.getTextRange.getEndOffset + 1, "}")
      }
    }
  }

  private def startLine(doc: Document, psiElement: PsiElement): Int = {
    doc.getLineNumber(psiElement.getTextRange.getStartOffset)
  }
}

