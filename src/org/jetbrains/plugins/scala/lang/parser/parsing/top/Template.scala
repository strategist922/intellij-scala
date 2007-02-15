package org.jetbrains.plugins.scala.lang.parser.parsing.top.template {

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementTypes
import org.jetbrains.plugins.scala.lang.parser.parsing.base.Constructor
import org.jetbrains.plugins.scala.lang.parser.parsing.base.Import
import org.jetbrains.plugins.scala.lang.parser.parsing.base.AttributeClause
import org.jetbrains.plugins.scala.lang.parser.parsing.base.Modifiers
import org.jetbrains.plugins.scala.lang.parser.parsing.base.Ids
import org.jetbrains.plugins.scala.lang.parser.util.ParserUtils
import org.jetbrains.plugins.scala.util.DebugPrint
import org.jetbrains.plugins.scala.lang.parser.parsing.types.Type
import org.jetbrains.plugins.scala.lang.parser.parsing.types.SimpleType
import org.jetbrains.plugins.scala.lang.parser.bnf.BNF
import org.jetbrains.plugins.scala.lang.parser.parsing.expressions.Expr
import org.jetbrains.plugins.scala.lang.parser.parsing.base.StatementSeparator

/**
 * User: Dmitry.Krasilschikov
 * Date: 30.10.2006
 * Time: 15:04:19
 */

/*
 *  Template ::= TemplateParents [TemplateBody]
 */
 
object Template extends Constr{
  override def getElementType = ScalaElementTypes.TEMPLATE

  override def parseBody (builder : PsiBuilder) : Unit = {
    if (BNF.firstTemplateParents.contains(builder.getTokenType)){
      new TemplateParents parse builder
    } else builder error "expected template parents"

    if (BNF.firstTemplateBody.contains(builder.getTokenType)){
      TemplateBody parse builder
    }
  }
} 

/*
 *  TemplateParents ::= Constr {with SimpleType}
 */

  class TemplateParents extends ConstrItem {
    override def getElementType = ScalaElementTypes.TEMPLATE_PARENTS

    override def first = BNF.firstTemplateParents
    
    override def parseBody(builder : PsiBuilder) : Unit = {
      if (BNF.firstTemplateParents.contains(builder.getTokenType)) {
        Constructor.parse(builder)
      } else builder.error("expected identifier")

      while (ScalaTokenTypes.kWITH.equals(builder.getTokenType)) {
        ParserUtils.eatElement(builder, ScalaTokenTypes.kWITH)

        if (BNF.firstSimpleType.contains(builder.getTokenType)) {
          SimpleType.parse(builder)
        }
      }
    }
  }

/*
 *  TemplateBody ::= �{� TemplateStatSeq �}�
 */  

  object TemplateBody extends Constr {
    override def getElementType = ScalaElementTypes.TEMPLATE_BODY

    override def parseBody(builder : PsiBuilder) : Unit = {
      DebugPrint println ("templateBody: " + builder.getTokenType)

      if (ScalaTokenTypes.tLBRACE.equals(builder.getTokenType)) {
        ParserUtils.eatElement(builder, ScalaTokenTypes.tLBRACE)
      } else {
        builder error "expected '{'"
        return
      }

      if (BNF.firstTemplateStatSeq.contains(builder.getTokenType)) {
        TemplateStatSeq parse builder
      }

      if (ScalaTokenTypes.tRBRACE.equals(builder.getTokenType)) {
        ParserUtils.eatElement(builder, ScalaTokenTypes.tRBRACE)
      } else {
        builder error "expected '}'"
        return
      }
    }
}

/*
 *  TemplateStatSeq ::= [TemplateStat] {StatementSeparator [TemplateStat}]
 */

 /*
  object TemplateStatSeq extends ConstrWithoutNode {
    override def parseBody(builder : PsiBuilder) : Unit = {

      var isError = false;
      var isEnd = false;
      while (!builder.eof && !isError && !isEnd) {

        isError = false

        while (BNF.firstStatementSeparator.contains(builder.getTokenType)) {
          StatementSeparator parse builder
          DebugPrint println ("TemplateStatSeq: StatementSeparator parse " + builder.getTokenType)
        }

        //todo: it needs guarant, that TemplteStat.parse advance lexer not less than 1 token

        if (BNF.firstTemplateStat.contains(builder.getTokenType)) {
          TemplateStat.parse(builder)
        }

        if (ScalaTokenTypes.tRBRACE.equals(builder.getTokenType) || builder.eof) {
          isEnd = true;
        }

        if (!isEnd && !BNF.firstStatementSeparator.contains(builder.getTokenType)) {
          isError = true;
          builder error "expected line teminator or '}'"

           if (BNF.firstTemplateStat.contains(builder.getTokenType)) {
            isError = false;
          }

          if (ScalaTokenTypes.tWRONG.equals(builder.getTokenType)) {
            while (!builder.eof && (ScalaTokenTypes.tRBRACE.equals(builder.getTokenType)
                || ScalaTokenTypes.tRSQBRACKET.equals(builder.getTokenType)
                || ScalaTokenTypes.tRPARENTHIS.equals(builder.getTokenType))) {
              builder.advanceLexer
            }
          }
        }

        DebugPrint println ("TemplateStatSeq: token " + builder.getTokenType)
       } 
    }
  } */

   object TemplateStatSeq extends ConstrWithoutNode {
    override def parseBody (builder: PsiBuilder): Unit = {

      var isLocalError = false;
      var isError = false;
      var isEnd = false;

      while (!builder.eof && !isEnd) {
        DebugPrint println ("TemplateStatSeq: token " + builder.getTokenType)

        isLocalError = false

        while (BNF.firstStatementSeparator.contains(builder.getTokenType)) {
          StatementSeparator parse builder
          DebugPrint println ("TemplateStatSeq: StatementSeparator parsed, token " + builder.getTokenType)
        }

        if (BNF.firstTemplateStat.contains(builder.getTokenType)) {
          TemplateStat.parse(builder)
        }

        DebugPrint println ("TemplateStatSeq - TemplateStat: token " + builder.getTokenType)

        if (ScalaTokenTypes.tRBRACE.equals(builder.getTokenType) || builder.eof) {
          isEnd = true;
          return
        }

        if (/*!isEnd && */!BNF.firstStatementSeparator.contains(builder.getTokenType)) {
          isLocalError = true;
          builder error "template statement declaration error"

           builder.getTokenType match {
              case ScalaTokenTypes.tRBRACE |
                   ScalaTokenTypes.tRSQBRACKET |
                   ScalaTokenTypes.tRPARENTHIS  => return

              case _ => {}
            }


          if (!BNF.firstTemplateStat.contains(builder.getTokenType)) {
            tryParseSmth(builder)
          }
        }
       isError = isError || isLocalError
      }
    }

    def tryParseSmth (builder : PsiBuilder) : Unit = {
      var isAfterBlock = false;
      var unstructuredTrashMarker : PsiBuilder.Marker = builder.mark;

      while (!builder.eof){
//        if (ScalaTokenTypes.tRBRACE.equals(builder.getTokenType) || builder.eof) {
//          unstructuredTrashMarker.drop
//          return
//        }

        if (BNF.firstTemplateStat.contains(builder.getTokenType)) {
          TemplateStat parse builder
        } else {

          builder.getTokenType match {
            case ScalaTokenTypes.tLBRACE => unstructuredTrashMarker.done(ScalaElementTypes.TRASH); parseTemplateStatSeqInBlock(builder); return;//unstructuredTrashMarker = builder.mark
            case _ => {builder.advanceLexer}
          }
        }
      }

      unstructuredTrashMarker.drop
    }

    def parseTemplateStatSeqInBlock (builder : PsiBuilder) : Unit = {
      val trashBlockMarker = builder.mark

      builder.getTokenType match {
        case ScalaTokenTypes.tLBRACE |
             ScalaTokenTypes.tLSQBRACKET |
             ScalaTokenTypes.tLPARENTHIS => builder.advanceLexer

        case _ => {builder error "expected open brace"; trashBlockMarker.drop; return}
      }

      TemplateStatSeq parse builder

      builder.getTokenType match {
        case ScalaTokenTypes.tRBRACE |
             ScalaTokenTypes.tRSQBRACKET |
             ScalaTokenTypes.tRPARENTHIS  => builder.advanceLexer

        case _ => {}
      }

      trashBlockMarker.done(ScalaElementTypes.TRASH)
    }
  }

/*
 *  TemplateStat ::= Import
 *              | {AttributeClause} {Modifier} Def
 *              | {AttributeClause} {Modifier} Dcl
 *              | Expr
 */

  object TemplateStat extends ConstrUnpredict {
    override def parseBody(builder : PsiBuilder) : Unit = {

        DebugPrint println "template statement parsing"
        DebugPrint println ("token type : " + builder.getTokenType)
        
        if(ScalaTokenTypes.kIMPORT.equals(builder.getTokenType)) {
         Import parse builder
         return
        }

        var statementDefDclMarker = builder.mark()

        var isDefOrDcl = false
        while(BNF.firstAttributeClause.contains(builder.getTokenType)) {
         //Console.println("attribute clause invoke")
         AttributeClause parse builder
         //Console.println("attribute clause invoked")
         isDefOrDcl = true
        }

        while(BNF.firstModifier.contains(builder.getTokenType)) {
         //Console.println("modifier clause invoke")
         Modifiers parse builder
         //Console.println("modifier clause invoked")
         isDefOrDcl = true
        }

        var defOrDclElement : IElementType = ScalaElementTypes.WRONGWAY
        if (isDefOrDcl) {
          if (BNF.firstDclDef.contains(builder.getTokenType)) {
              defOrDclElement = (DclDef parseBodyNode builder)
              statementDefDclMarker.done(defOrDclElement)
          } else {
            //error, because def or dcl must be defined after attributeClause or Modifier
            builder error "expected definition or declaration"
            statementDefDclMarker.drop
          }

          return
        }

        if (BNF.firstDclDef.contains(builder.getTokenType)) {
          defOrDclElement = DclDef.parseBodyNode(builder)
          statementDefDclMarker.done(defOrDclElement)
          return
        }

        statementDefDclMarker.drop()

        if (BNF.firstExpr.contains(builder.getTokenType)) {
          val exprElementType = Expr parse builder
          if (ScalaElementTypes.WRONGWAY.equals(exprElementType)) {
            builder error "wrong expression"
            builder.advanceLexer
          }
          
          return
        }

        return

    }
  }

  
}