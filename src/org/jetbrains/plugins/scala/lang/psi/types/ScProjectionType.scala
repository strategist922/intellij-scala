package org.jetbrains.plugins.scala
package lang
package psi
package types

import impl.toplevel.synthetic.ScSyntheticClass
import org.jetbrains.plugins.scala.util.ScEquivalenceUtil
import api.toplevel.typedef._
import api.statements.{ScVariable, ScValue, ScTypeAliasDefinition, ScTypeAlias}
import result.{TypingContext, Success}
import api.toplevel.ScTypedDefinition
import resolve.processor.ResolveProcessor
import resolve.ResolveTargets
import com.intellij.psi.{PsiElement, PsiClass, ResolveState, PsiNamedElement}
import extensions.{toObjectExt, toPsiClassExt}
import collection.immutable.HashSet
import caches.CachesUtil
import com.intellij.psi.util.PsiModificationTracker
import impl.toplevel.templates.ScTemplateBodyImpl
import api.base.patterns.ScBindingPattern

/**
 * @author ilyas
 */

/**
 * This type means type projection:
 * SomeType#member
 * member can be class or type alias
 */
case class ScProjectionType(projected: ScType, element: PsiNamedElement,
                            superReference: Boolean /* todo: find a way to remove it*/) extends ValueType {

  override def removeAbstracts = ScProjectionType(projected.removeAbstracts, element, superReference)

  override def recursiveUpdate(update: ScType => (Boolean, ScType), visited: HashSet[ScType]): ScType = {
    if (visited.contains(this)) {
      return update(this) match {
        case (true, res) => res
        case _ => this
      }
    }
    update(this) match {
      case (true, res) => res
      case _ =>
        ScProjectionType(projected.recursiveUpdate(update, visited + this), element, superReference)
    }
  }

  override def recursiveVarianceUpdate(update: (ScType, Int) => (Boolean, ScType), variance: Int): ScType = {
    update(this, variance) match {
      case (true, res) => res
      case _ =>
        ScProjectionType(projected.recursiveVarianceUpdate(update, 0), element, superReference)
    }
  }

  private def actual: (PsiNamedElement, ScSubstitutor) = {
    def actualInner(element: PsiNamedElement, projected: ScType): Option[(PsiNamedElement, ScSubstitutor)] = {
      val emptySubst = new ScSubstitutor(Map.empty, Map.empty, Some(projected))
      val resolvePlace = {
        def fromClazz(clazz: ScTypeDefinition): PsiElement = {
          clazz.extendsBlock.templateBody.flatMap(_.asInstanceOf[ScTemplateBodyImpl].getLastChildStub.toOption).
            getOrElse(clazz.extendsBlock)
        }
        ScType.extractClass(projected, Some(element.getProject)) match {
          case Some(clazz: ScTypeDefinition) => fromClazz(clazz)
          case _ => projected match {
            case ScThisType(clazz: ScTypeDefinition) => fromClazz(clazz)
            case _ => element
          }
        }
      }
      element match {
        case a: ScTypeAlias => {
          val name = a.name
          import ResolveTargets._
          val proc = new ResolveProcessor(ValueSet(CLASS), resolvePlace, name)
          proc.processType(projected, resolvePlace, ResolveState.initial)
          val candidates = proc.candidates
          if (candidates.length == 1 && candidates(0).element.isInstanceOf[PsiNamedElement]) {
            Some(candidates(0).element, emptySubst followed candidates(0).substitutor)
          } else None
        }
        case d: ScTypedDefinition if d.isStable => {
          val name = d.name
          import ResolveTargets._
          val proc = new ResolveProcessor(ValueSet(VAL, OBJECT), resolvePlace, name)
          proc.processType(projected, resolvePlace, ResolveState.initial)
          val candidates = proc.candidates
          if (candidates.length == 1 && candidates(0).element.isInstanceOf[PsiNamedElement]) {
            Some(candidates(0).element, emptySubst followed candidates(0).substitutor)
          } else None
        }
        case d: ScTypeDefinition => {
          val name = d.name
          import ResolveTargets._
          val proc = new ResolveProcessor(ValueSet(CLASS), resolvePlace, name) //ScObject in ScTypedDefinition case.
          proc.processType(projected, resolvePlace, ResolveState.initial)
          val candidates = proc.candidates
          if (candidates.length == 1 && candidates(0).element.isInstanceOf[PsiNamedElement]) {
            Some(candidates(0).element, emptySubst followed candidates(0).substitutor)
          } else None
        }
        case _ => None
      }
    }

    if (superReference) return (element, ScSubstitutor.empty) //todo: probably it's wrong to pass empty substitutor?

    val (actualElement, actualSubst) =
      CachesUtil.getMappedWithRecursionPreventingWithRollback[PsiNamedElement, ScType, Option[(PsiNamedElement, ScSubstitutor)]](
        element, projected, CachesUtil.PROJECTION_TYPE_ACTUAL_INNER, actualInner, None,
        PsiModificationTracker.MODIFICATION_COUNT).getOrElse(
          (element, ScSubstitutor.empty)
        )
    (actualElement, new ScSubstitutor(Map.empty, Map.empty, Some(projected)) followed actualSubst)
  }

  def actualElement: PsiNamedElement = actual._1
  def actualSubst: ScSubstitutor = actual._2

  override def equivInner(r: ScType, uSubst: ScUndefinedSubstitutor,
                          falseUndef: Boolean): (Boolean, ScUndefinedSubstitutor) = {
    if (actualElement.isInstanceOf[ScBindingPattern] &&
      actualElement.asInstanceOf[ScBindingPattern].nameContext.isInstanceOf[ScValue]) {
      val a = actualElement.asInstanceOf[ScBindingPattern]
      val subst = actualSubst
      val tp = subst.subst(a.getType(TypingContext.empty).getOrAny)
      if (ScType.isSingletonType(tp)) {
        val resInner = Equivalence.equivInner(tp, r, uSubst, falseUndef)
        if (resInner._1) return resInner
      }
    }
    Conformance.isAliasType(this) match {
      case Some(Conformance.AliasType(ta: ScTypeAliasDefinition, lower, _)) =>
        return Equivalence.equivInner(lower match {
          case Success(tp, _) => tp
          case _ => return (false, uSubst)
        }, r, uSubst, falseUndef)
      case _ =>
    }
    r match {
      case t: StdType =>
        element match {
          case synth: ScSyntheticClass => Equivalence.equivInner(synth.t, t, uSubst, falseUndef)
          case _ => (false, uSubst)
        }
      case param@ScParameterizedType(proj2@ScProjectionType(p1, element1, _), typeArgs) =>
        proj2.actualElement match {
          case ta: ScTypeAliasDefinition =>
            Conformance.isAliasType(r) match {
              case Some(Conformance.AliasType(ta: ScTypeAliasDefinition, lower, _)) =>
                Equivalence.equivInner(this, lower match {
                  case Success(tp, _) => tp
                  case _ => return (false, uSubst)
                }, uSubst, falseUndef)
              case _ => (false, uSubst)
            }
          case _ => (false, uSubst)
        }
      case proj2@ScProjectionType(p1, element1, _) => {
        proj2.actualElement match {
          case a: ScTypeAliasDefinition =>
            Conformance.isAliasType(r) match {
              case Some(Conformance.AliasType(ta: ScTypeAliasDefinition, lower, _)) =>
                Equivalence.equivInner(this, lower match {
                  case Success(tp, _) => tp
                  case _ => return (false, uSubst)
                }, uSubst, falseUndef)
              case _ =>
            }
          case _ =>
        }
        if (actualElement != proj2.actualElement) {
          actualElement match {
            case o: ScObject =>
            case t: ScTypedDefinition if t.isStable =>
              val s: ScSubstitutor = new ScSubstitutor(Map.empty, Map.empty, Some(projected)) followed actualSubst
              t.getType(TypingContext.empty) match {
                case Success(tp, _) if ScType.isSingletonType(tp) =>
                  return Equivalence.equivInner(s.subst(tp), r, uSubst, falseUndef)
                case _ =>
              }
            case _ =>
          }
          proj2.actualElement match {
            case o: ScObject =>
            case t: ScTypedDefinition =>
              val s: ScSubstitutor =
                new ScSubstitutor(Map.empty, Map.empty, Some(p1)) followed proj2.actualSubst
              t.getType(TypingContext.empty) match {
                case Success(tp, _) if ScType.isSingletonType(tp) =>
                  return Equivalence.equivInner(s.subst(tp), this, uSubst, falseUndef)
                case _ =>
              }
            case _ =>
          }
          return (false, uSubst)
        }
        Equivalence.equivInner(projected, p1, uSubst, falseUndef)
      }
      case ScThisType(clazz) =>
        element match {
          case o: ScObject => (false, uSubst)
          case t: ScTypedDefinition if t.isStable =>
            t.getType(TypingContext.empty) match {
              case Success(singl, _) if ScType.isSingletonType(singl) =>
                val newSubst = actualSubst.followed(new ScSubstitutor(Map.empty, Map.empty, Some(projected)))
                Equivalence.equivInner(r, newSubst.subst(singl), uSubst, falseUndef)
              case _ => (false, uSubst)
            }
          case _ => (false, uSubst)
        }
      case _ => (false, uSubst)
    }
  }

  def visitType(visitor: ScalaTypeVisitor) {
    visitor.visitProjectionType(this)
  }
}

/**
 * This type means type, which depends on place, where you want to get expression type.
 * For example
 *
 * class A       {
 *   def foo: this.type = this
 * }
 *
 * class B extneds A       {
 *   val z = foo // <- type in this place is B.this.type, not A.this.type
 * }
 *
 * So when expression is typed, we should replace all such types be return value.
 */
case class ScThisType(clazz: ScTemplateDefinition) extends ValueType {

  override def equivInner(r: ScType, uSubst: ScUndefinedSubstitutor,
                          falseUndef: Boolean): (Boolean, ScUndefinedSubstitutor) = {
    (this, r) match {
      case (ScThisType(clazz1), ScThisType(clazz2)) =>
        (ScEquivalenceUtil.areClassesEquivalent(clazz1, clazz2), uSubst)
      case (ScThisType(obj1: ScObject), ScDesignatorType(obj2: ScObject)) =>
        (ScEquivalenceUtil.areClassesEquivalent(obj1, obj2), uSubst)
      case (_, ScDesignatorType(obj: ScObject)) =>
        (false, uSubst)
      case (_, ScDesignatorType(typed: ScTypedDefinition)) if typed.isStable =>
        typed.getType(TypingContext.empty) match {
          case Success(tp, _) if ScType.isSingletonType(tp) =>
            Equivalence.equivInner(this, tp, uSubst, falseUndef)
          case _ =>
            (false, uSubst)
        }
      case (_, ScProjectionType(_, o: ScObject, _)) => (false, uSubst)
      case (_, p@ScProjectionType(tp, elem: ScTypedDefinition, _)) if elem.isStable =>
        elem.getType(TypingContext.empty) match {
          case Success(singl, _) if ScType.isSingletonType(singl) =>
            val newSubst = p.actualSubst.followed(new ScSubstitutor(Map.empty, Map.empty, Some(tp)))
            Equivalence.equivInner(this, newSubst.subst(singl), uSubst, falseUndef)
          case _ => (false, uSubst)
        }
      case _ => (false, uSubst)
    }
  }

  def visitType(visitor: ScalaTypeVisitor) {
    visitor.visitThisType(this)
  }
}

/**
 * This type means normal designator type.
 * It can be whether singleton type (v.type) or simple type (java.lang.String).
 * element can be any stable element, class, value or type alias
 */
case class ScDesignatorType(element: PsiNamedElement) extends ValueType {
  override def getValType: Option[StdType] = {
    element match {
      case o: ScObject => None
      case clazz: PsiClass =>
        ScType.baseTypesQualMap.get(clazz.qualifiedName)
      case _ => None
    }
  }

  private var isStaticClass = false
  /**
   * You can use this method to check if it's Java class,
   * which is used for getting static context => no implicit conversion
   */
  def isStatic = isStaticClass
  def this(elem: PsiNamedElement, isStaticClass: Boolean) {
    this (elem)
    this.isStaticClass = isStaticClass
  }

  override def equivInner(r: ScType, uSubst: ScUndefinedSubstitutor,
                          falseUndef: Boolean): (Boolean, ScUndefinedSubstitutor) = {
    (this, r) match {
      case (ScDesignatorType(a: ScTypeAliasDefinition), _) =>
        Equivalence.equivInner(a.aliasedType match {
          case Success(tp, _) => tp
          case _ => return (false, uSubst)
        }, r, uSubst, falseUndef)
      case (_, ScDesignatorType(element1)) =>
        if (ScEquivalenceUtil.smartEquivalence(element, element1)) return (true, uSubst)
        if (ScType.isSingletonType(this) && ScType.isSingletonType(r)) {
          element match {
            case o: ScObject =>
            case bind: ScTypedDefinition if bind.isStable =>
              bind.getType(TypingContext.empty) match {
                case Success(tp, _) if ScType.isSingletonType(tp) =>
                  return Equivalence.equivInner(tp, r, uSubst, falseUndef)
                case _ =>
              }
            case _ =>
          }
          element1 match {
            case o: ScObject =>
            case bind: ScTypedDefinition if bind.isStable =>
              bind.getType(TypingContext.empty) match {
                case Success(tp, _) if ScType.isSingletonType(tp) =>
                  return Equivalence.equivInner(tp, this, uSubst, falseUndef)
                case _ =>
              }
          }
        }
        (false, uSubst)
      case _ => (false, uSubst)
    }
  }

  def visitType(visitor: ScalaTypeVisitor) {
    visitor.visitDesignatorType(this)
  }
}
