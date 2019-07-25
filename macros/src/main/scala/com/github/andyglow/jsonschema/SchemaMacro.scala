package com.github.andyglow.jsonschema

import java.net.{URI, URL}
import java.util.UUID

import scala.reflect.macros.blackbox

object SchemaMacro {

  def impl[T : c.WeakTypeTag](c: blackbox.Context): c.Expr[json.Schema[T]] = {
    import c.universe._

    val jsonPkg   = q"_root_.json"
    val scalaPkg  = q"_root_.scala"
    val schemaObj = q"$jsonPkg.Schema"

    val subject             = weakTypeOf[T]
    val optionTpe           = weakTypeOf[Option[_]]
    val setTpe              = weakTypeOf[Set[_]]
    val jsonTypeConstructor = weakTypeOf[json.Schema[_]].typeConstructor
    val jsonSubject         = appliedType(jsonTypeConstructor, subject)

    def resolve(tpe: Type, stack: List[Type]): Tree = {
      if (stack contains tpe) c.error(c.enclosingPosition, s"cyclic dependency for $tpe")

      val integer = q"$schemaObj.`integer`"
      val number  = q"$schemaObj.`number`"
      val string  = q"$schemaObj.`string`"
      val some    = q"$scalaPkg.Some"
      val none    = q"$scalaPkg.None"

      def genTree: Tree = tpe match {
        // boolean
        case x if x =:= typeOf[Boolean]                 => q"$schemaObj.`boolean`"

        // numeric
        case x if x =:= typeOf[Byte]                    => q"$number[$x]()"
        case x if x =:= typeOf[Short]                   => q"$number[$x]()"
        case x if x =:= typeOf[Int]                     => integer
        case x if x =:= typeOf[Double]                  => q"$number[$x]()"
        case x if x =:= typeOf[Float]                   => q"$number[$x]()"
        case x if x =:= typeOf[Long]                    => q"$number[$x]()"
        case x if x =:= typeOf[BigInt]                  => q"$number[$x]()"
        case x if x =:= typeOf[BigDecimal]              => q"$number[$x]()"

        // string
        case x if x =:= typeOf[String]                  => q"$string[$scalaPkg.Predef.String]($none, $none)"
        case x if x =:= typeOf[Char]                    => q"""$string[$x]($none, $some("^[.\\s]$$"))"""

        // uuid
        case x if x =:= typeOf[UUID]                    => q"""$string[$x]($none, $some("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$$"))"""

        // url, uri
        case x if x =:= typeOf[URL]                     => q"$string[$x]($some($string.Format.`uri`), $none)"
        case x if x =:= typeOf[URI]                     => q"$string[$x]($some($string.Format.`uri`), $none)"

        // date, date-time
        case x if x =:= typeOf[java.util.Date]          => q"$string[$x]($some($string.Format.`date-time`), $none)"
        case x if x =:= typeOf[java.sql.Timestamp]      => q"$string[$x]($some($string.Format.`date-time`), $none)"
        case x if x =:= typeOf[java.time.Instant]       => q"$string[$x]($some($string.Format.`date-time`), $none)"
        case x if x =:= typeOf[java.time.LocalDateTime] => q"$string[$x]($some($string.Format.`date-time`), $none)"
        case x if x =:= typeOf[java.sql.Date]           => q"$string[$x]($some($string.Format.`date`), $none)"
        case x if x =:= typeOf[java.time.LocalDate]     => q"$string[$x]($some($string.Format.`date`), $none)"
        case x if x =:= typeOf[java.sql.Time]           => q"$string[$x]($some($string.Format.`time`), $none)"
        case x if x =:= typeOf[java.time.LocalTime]     => q"$string[$x]($some($string.Format.`time`), $none)"

        case x if x <:< typeOf[Map[String, _]]          => SM.gen(x, stack)

        case x if x <:< typeOf[Map[Int, _]]             => IM.gen(x, stack)

        case x if x <:< typeOf[Array[_]]                => Arr.gen(x, stack)
        case x if x <:< typeOf[Iterable[_]]             => Arr.gen(x, stack)

        case SE(names)                                  => SE.gen(tpe, names)

        case SC(subTypes)                               => SC.gen(tpe, subTypes, stack)

        case CC(fields)                                 => CC.gen(fields, tpe, stack)

        case VC(innerType)                              => VC.gen(innerType, tpe, stack)

        case _ =>
          c.error(c.enclosingPosition, s"schema for $tpe is not supported, ${stack mkString " :: "}")
          q"null"
      }

      Implicit.getOrElse(tpe, genTree)
    }

    object Implicit {

      def getOrElse(tpe: Type, gen: => Tree): Tree = {
        val typeType = appliedType(jsonTypeConstructor, tpe)

        if (typeType =:= jsonSubject) gen else {
          c.inferImplicitValue(typeType) match {
            case EmptyTree  => gen
            case x          => q"""$schemaObj.`ref`[$tpe]($jsonPkg.Json.sig[$tpe].signature, $x)"""
          }
        }
      }
    }

    object SE {

      def unapply(tpe: Type): Option[Set[String]] = {
        if (tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.isSealed) {
          val instances = tpe.typeSymbol.asClass.knownDirectSubclasses

          if (instances forall { i => val c = i.asClass; c.isModuleClass && c.isCaseClass}) {
            Some(instances map { _.name.decodedName.toString })
          } else
            None
        } else
          None
      }

      def gen(tpe: Type, names: Set[String]): Tree = q"$schemaObj.`enum`[$tpe]($names)"
    }

    object SC {

      def unapply(tpe: Type): Option[Set[Type]] = {
        if (tpe.typeSymbol.isClass && tpe.typeSymbol.asClass.isSealed) {
          val instances = tpe.typeSymbol.asClass.knownDirectSubclasses

          if (instances forall { i => val c = i.asClass; !c.isModuleClass && c.isCaseClass}) {
            Some(instances map { _.typeSignature })
          } else
            None
        } else
          None
      }

      def gen(tpe: Type, subTypes: Set[Type], stack: List[Type]): Tree = {
        val trees = subTypes collect {
          case CC(fields)    => CC.gen(fields, tpe, stack)
          case VC(innerType) => VC.gen(innerType, tpe, stack)
        }

        q"$schemaObj.`oneof`[$tpe]($trees)"
      }
    }

    object CC {

      // TODO: add support for case classes defined in method body

      final def lookupCompanionOf(clazz: Symbol): Symbol = clazz.companion

      def possibleApplyMethodsOf(tpe: Type): List[MethodSymbol] = {
        val subjectCompanionSym = tpe.typeSymbol
        val subjectCompanion    = lookupCompanionOf(subjectCompanionSym)
        val subjectCompanionTpe = subjectCompanion.typeSignature

        subjectCompanionTpe.decl(TermName("apply")) match {

          case NoSymbol =>
            c.abort(c.enclosingPosition, s"No apply function found for ${subjectCompanion.fullName}")

          case x => x.asTerm.alternatives flatMap { apply =>
            val method = apply.asMethod

            def areAllImplicit(pss: List[List[Symbol]]): Boolean = pss forall {
              case p :: _ => p.isImplicit
              case _      => false
            }

            method.paramLists match {
              case ps :: pss if ps.nonEmpty && areAllImplicit(pss) => List(method)
              case _ => List.empty
            }
          }
        }
      }

      def applyMethod(tpe: Type): Option[MethodSymbol] = possibleApplyMethodsOf(tpe).headOption

      case class Field(
        name: TermName,
        tpe: Type,
        effectiveTpe: Type,
        annotations: List[Annotation],
        hasDefault: Boolean,
        isOption: Boolean)

      def fieldMap(tpe: Type): Seq[Field] = {

        val annotationMap = tpe.decls.collect {

          case s: MethodSymbol if s.isCaseAccessor =>
            // workaround: force loading annotations
            s.typeSignature
            s.accessed.annotations.foreach(_.tree.tpe)

            s.name.toString.trim -> s.accessed.annotations
        }.toMap

        def toField(fieldSym: TermSymbol): Field = {
          val name        = fieldSym.name.toString.trim
          val fieldTpe    = fieldSym.typeSignature
          val isOption    = fieldTpe <:< optionTpe

          Field(
            name          = TermName(name),
            tpe           = fieldTpe,
            effectiveTpe  = if (isOption) fieldTpe.typeArgs.head else fieldTpe,
            annotations   = annotationMap.getOrElse(name, List.empty),
            isOption      = isOption,
            hasDefault    = fieldSym.isParamWithDefault)
        }

        val fields = applyMethod(tpe) flatMap { method =>
          method.paramLists.headOption map { params =>
            val fields = params map { _.asTerm } map toField

            fields.toSeq
          }
        }

        fields getOrElse Seq.empty
      }

      def unapply(tpe: Type): Option[Seq[CC.Field]] = {
        val symbol = tpe.typeSymbol

        if (symbol.isClass) {
          val clazz = symbol.asClass
          if (clazz.isCaseClass) {
            if (clazz.isDerivedValueClass) None else Some(fieldMap(tpe))
          } else
            None
        } else
          None
      }

      def gen(fieldMap: Seq[CC.Field], tpe: Type, stack: List[Type]): Tree = {
        val obj = q"$schemaObj.`object`"
        val fields = fieldMap map { f =>
          val name      = f.name.decodedName.toString
          val jsonType  = resolve(f.effectiveTpe, if (f.isOption) stack else tpe +: stack)

          q"$obj.Field[${f.effectiveTpe}](name = $name, tpe = $jsonType, required = ${ !f.isOption && !f.hasDefault })"
        }

        q"$obj[$tpe](..$fields)"
      }
    }

    object IM {

      def gen(tpe: Type, stack: List[Type]): Tree = {
        val componentType = tpe.typeArgs.tail.head
        val componentJsonType = resolve(componentType, tpe +: stack)

        q"""$schemaObj.`int-map`[$componentType]($componentJsonType)"""
      }
    }

    object SM {

      def gen(tpe: Type, stack: List[Type]): Tree = {
        val componentType = tpe.typeArgs.tail.head
        val componentJsonType = resolve(componentType, tpe +: stack)

        q"""$schemaObj.`string-map`[$componentType]($componentJsonType)"""
      }
    }

    object VC {

      def unapply(x: Type): Option[Type] = {
        val symbol = x.typeSymbol

        if (symbol.isClass) {
          val clazz = symbol.asClass
          if (clazz.isCaseClass) {
            if (clazz.isDerivedValueClass) Some {
              clazz.primaryConstructor.asMethod.paramLists.head.head.typeSignature
            } else
              None
          } else
            None
        } else
          None
      }

      def gen(innerType: Type, tpe: Type, stack: List[Type]): Tree = {
        val x = resolve(innerType, tpe +: stack)
        x match {
          case q"""$c[$t](..$args)""" => q"$c[$tpe](..$args)"
          case x => x
        }
      }
    }

    object Arr {

      def gen(tpe: Type, stack: List[Type]): Tree = {
        val componentType     = tpe.typeArgs.head
        val componentJsonType = resolve(componentType, tpe +: stack)
        val isSet             = tpe <:< setTpe

        if (isSet)
          q"""$schemaObj.`set`[$componentType, ${tpe.typeConstructor}]($componentJsonType)"""
        else
          q"""$schemaObj.`array`[$componentType, ${tpe.typeConstructor}]($componentJsonType)"""
      }
    }

    val out = resolve(subject, Nil)

    c.Expr[json.Schema[T]](out)
  }
}
