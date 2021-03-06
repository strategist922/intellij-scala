package org.jetbrains.plugins.scala
package codeInspection.recursion

import codeInspection.{AbstractFix, AbstractInspection}
import com.intellij.openapi.project.Project
import com.intellij.codeInspection.{ProblemDescriptor, ProblemsHolder}
import lang.psi.api.statements.{ScAnnotationsHolder, RecursionType, ScFunctionDefinition}

/**
 * Pavel Fatin
 */

class NoTailRecursionAnnotationInspection extends AbstractInspection("No tail recursion annotation") {
  def actionFor(holder: ProblemsHolder) = {
    case f: ScFunctionDefinition if f.canBeTailRecursive && !f.hasTailRecursionAnnotation &&
            f.recursionType == RecursionType.TailRecursion =>
      holder.registerProblem(f.nameId, getDisplayName, new AddAnnotationQuickFix(f))
  }

  class AddAnnotationQuickFix(holder: ScAnnotationsHolder)
          extends AbstractFix("Add @tailrec annotation", holder) {
    def doApplyFix(project: Project, descriptor: ProblemDescriptor) {
      holder.addAnnotation("scala.annotation.tailrec")
    }
  }
}
