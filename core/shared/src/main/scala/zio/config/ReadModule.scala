package zio.config

import com.github.ghik.silencer.silent
import zio.config.ReadError._

@silent("Unused import")
private[config] trait ReadModule extends ConfigDescriptorModule {
  import VersionSpecificSupport._

  final def read[A](
    configuration: ConfigDescriptor[A]
  ): Either[ReadError[K], A] = {
    type Res[+B] = Either[ReadError[K], AnnotatedRead[B]]

    import ConfigDescriptorAdt._

    def formatError(paths: List[Step[K]], actualType: String, expectedType: String, descriptions: List[String]) =
      Left(
        ReadError.FormatError(
          paths.reverse,
          s"Provided value is of type $actualType, expecting the type $expectedType",
          descriptions
        )
      )

    def loopNested[B](
      path: List[Step[K]],
      keys: List[K],
      cfg: Nested[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[B] = {
      val updatedKeys = cfg.path :: keys
      val updatedPath = Step.Key(cfg.path) :: path
      loopAny(updatedPath, updatedKeys, cfg.config, descriptions, programSummary)
    }

    def loopOptional[B](
      path: List[Step[K]],
      keys: List[K],
      cfg: Optional[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[Option[B]] =
      loopAny(path, keys, cfg.config, descriptions, programSummary) match {
        case Left(error) =>
          handleDefaultValues(error, cfg.config, None)

        case Right(value) =>
          Right(AnnotatedRead(Some(value.value), Set(AnnotatedRead.Annotation.NonDefaultValue) ++ value.annotations))
      }

    def loopDefault[B](
      path: List[Step[K]],
      keys: List[K],
      cfg: Default[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[B] =
      loopAny(path, keys, cfg.config, descriptions, programSummary) match {
        case Left(error) =>
          handleDefaultValues(error, cfg.config, cfg.default)

        case Right(value) =>
          Right(AnnotatedRead(value.value, Set(AnnotatedRead.Annotation.NonDefaultValue) ++ value.annotations))
      }

    def loopOrElse[B](
      path: List[Step[K]],
      keys: List[K],
      cfg: OrElse[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[B] =
      loopAny(path, keys, cfg.left, descriptions, programSummary) match {
        case a @ Right(_)    => a
        case Left(leftError) =>
          loopAny(path, keys, cfg.right, descriptions, programSummary) match {
            case a @ Right(_)     => a
            case Left(rightError) =>
              Left(ReadError.OrErrors(leftError :: rightError :: Nil, leftError.annotations ++ rightError.annotations))
          }
      }

    def loopOrElseEither[B, C](
      path: List[Step[K]],
      keys: List[K],
      cfg: OrElseEither[B, C],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[Either[B, C]] =
      loopAny(path, keys, cfg.left, descriptions, programSummary) match {
        case Right(value) =>
          Right(value.map(Left(_)))

        case Left(leftError) =>
          loopAny(path, keys, cfg.right, descriptions, programSummary) match {
            case Right(rightValue) =>
              Right(rightValue.map(Right(_)))

            case Left(rightError) =>
              Left(ReadError.OrErrors(leftError :: rightError :: Nil, leftError.annotations ++ rightError.annotations))
          }
      }

    def loopSource[B](path: List[Step[K]], keys: List[K], cfg: Source[B], descriptions: List[String]): Res[B] =
      cfg.source.getConfigValue(keys.reverse) match {
        case PropertyTree.Empty       => Left(ReadError.MissingValue(path.reverse, descriptions))
        case PropertyTree.Record(_)   => formatError(path, "Record", "Leaf", descriptions)
        case PropertyTree.Sequence(_) => formatError(path, "Sequence", "Leaf", descriptions)
        case PropertyTree.Leaf(value) =>
          cfg.propertyType.read(value) match {
            case Left(parseError) =>
              Left(
                ReadError.FormatError(
                  path.reverse,
                  parseErrorMessage(
                    parseError.value.toString,
                    parseError.typeInfo
                  )
                )
              )
            case Right(parsed)    =>
              Right(AnnotatedRead(parsed, Set.empty))
          }
      }

    def loopZip[B, C](
      path: List[Step[K]],
      keys: List[K],
      cfg: Zip[B, C],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[(B, C)] =
      (
        loopAny(path, keys, cfg.left, descriptions, programSummary),
        loopAny(path, keys, cfg.right, descriptions, programSummary)
      ) match {
        case (Right(leftV), Right(rightV)) =>
          Right(leftV.zip(rightV))

        case (Left(error1), Left(error2)) =>
          Left(ZipErrors(error1 :: error2 :: Nil, error1.annotations ++ error2.annotations))

        case (Left(error), Right(annotated)) =>
          Left(ZipErrors(error :: Nil, error.annotations ++ annotated.annotations))

        case (Right(annotated), Left(error)) =>
          Left(ZipErrors(error :: Nil, error.annotations ++ annotated.annotations))
      }

    def loopXmapEither[B, C](
      path: List[Step[K]],
      keys: List[K],
      cfg: TransformOrFail[B, C],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[C] =
      loopAny(path, keys, cfg.config, descriptions, programSummary) match {
        case Left(error) => Left(error)
        case Right(a)    =>
          a.mapError(cfg.f).swap.map(message => ReadError.ConversionError(path.reverse, message, a.annotations)).swap
      }

    def loopMap[B](
      path: List[Step[K]],
      keys: List[K],
      cfg: DynamicMap[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[Map[K, B]] =
      cfg.source.getConfigValue(keys.reverse) match {
        case PropertyTree.Leaf(_)        => formatError(path, "Leaf", "Record", descriptions)
        case PropertyTree.Sequence(_)    => formatError(path, "Sequence", "Record", descriptions)
        case PropertyTree.Record(values) =>
          val result: List[(K, Res[B])] = values.toList.map { case ((k, tree)) =>
            val source: ConfigSource =
              getConfigSource(cfg.source.names, tree.getPath, cfg.source.leafForSequence)

            (
              k,
              loopAny(
                Step.Key(k) :: path,
                Nil,
                cfg.config.updateSource(_ => source),
                descriptions,
                programSummary
              )
            )
          }

          seqMap2[K, ReadError[K], B](result.map({ case (a, b) => (a, b.map(_.value)) }).toMap).swap
            .map(errs => ReadError.MapErrors(errs, errs.flatMap(_.annotations).toSet))
            .swap
            .map(mapp => AnnotatedRead(mapp, Set.empty))

        case PropertyTree.Empty => Left(ReadError.MissingValue(path.reverse, descriptions))
      }

    def loopSequence[B](
      path: List[Step[K]],
      keys: List[K],
      cfg: Sequence[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[List[B]] = {
      def fromTrees(values: List[PropertyTree[K, V]]) = {
        val list = values.zipWithIndex.map { case (tree, idx) =>
          val source =
            getConfigSource(cfg.source.names, tree.getPath, cfg.source.leafForSequence)
          loopAny(
            Step.Index(idx) :: path,
            Nil,
            cfg.config.updateSource(_ => source),
            descriptions,
            programSummary
          )
        }

        seqEither2[ReadError[K], B, ReadError[K]]((_, a) => a)(list.map(res => res.map(_.value))).swap
          .map(errs => ReadError.ListErrors(errs, errs.flatMap(_.annotations).toSet))
          .swap
          .map(list => AnnotatedRead(list, Set.empty))
      }

      cfg.source.getConfigValue(keys.reverse) match {
        case leaf @ PropertyTree.Leaf(_) =>
          cfg.source.leafForSequence match {
            case LeafForSequence.Invalid => formatError(path, "Leaf", "Sequence", descriptions)
            case LeafForSequence.Valid   => fromTrees(List(leaf))
          }

        case PropertyTree.Record(_)        => formatError(path, "Record", "Sequence", descriptions)
        case PropertyTree.Empty            => Left(ReadError.MissingValue(path.reverse, descriptions))
        case PropertyTree.Sequence(values) => fromTrees(values)
      }
    }

    def loopAny[B](
      path: List[Step[K]],
      keys: List[K],
      config: ConfigDescriptor[B],
      descriptions: List[String],
      programSummary: List[ConfigDescriptor[_]]
    ): Res[B] =
      // This is to handle recursive configs
      if (programSummary.contains(config) && isEmptyConfigSource(config, keys.reverse))
        Left(ReadError.MissingValue(path.reverse, descriptions))
      else
        config match {
          case c @ Lazy(thunk) =>
            loopAny(path, keys, thunk(), descriptions, c :: programSummary)

          case c @ Default(_, _) =>
            loopDefault(path, keys, c, descriptions, c :: programSummary)

          case c @ Describe(_, message) =>
            loopAny(path, keys, c.config, descriptions :+ message, c :: programSummary)

          case c @ DynamicMap(_, _) =>
            loopMap(path, keys, c, descriptions, c :: programSummary)

          case c @ Nested(_, _, _) =>
            loopNested(path, keys, c, descriptions, c :: programSummary)

          case c @ Optional(_) =>
            loopOptional(path, keys, c, descriptions, c :: programSummary)

          case c @ OrElse(_, _) =>
            loopOrElse(path, keys, c, descriptions, c :: programSummary)

          case c @ OrElseEither(_, _) =>
            loopOrElseEither(path, keys, c, descriptions, c :: programSummary)

          case c @ Source(_, _) =>
            loopSource(path, keys, c, descriptions)

          case c @ Zip(_, _) =>
            loopZip(path, keys, c, descriptions, c :: programSummary)

          case c @ TransformOrFail(_, _, _) =>
            loopXmapEither(path, keys, c, descriptions, c :: programSummary)

          case c @ Sequence(_, _) =>
            loopSequence(path, keys, c, descriptions, c :: programSummary)
        }

    loopAny(Nil, Nil, configuration, Nil, Nil).map(_.value)

  }

  private[config] def isEmptyConfigSource[A](
    config: ConfigDescriptor[A],
    keys: List[K]
  ): Boolean = {
    val sourceTrees = config.sources.map(_.getConfigValue(keys))
    sourceTrees.forall(_ == PropertyTree.empty)
  }

  private[config] def foldReadError[B](
    error: ReadError[K]
  )(alternative: B)(f: PartialFunction[ReadError[K], B])(g: (B, B) => B, zero: B): B = {
    def go(list: List[ReadError[K]]): B =
      list.foldLeft(zero)((a, b) => g(foldReadError(b)(alternative)(f)(g, zero), a))

    error match {
      case e @ ReadError.MissingValue(_, _, _)    => f.applyOrElse(e, (_: ReadError[K]) => alternative)
      case e @ ReadError.SourceError(_, _)        => f.applyOrElse(e, (_: ReadError[K]) => alternative)
      case e @ ReadError.FormatError(_, _, _, _)  => f.applyOrElse(e, (_: ReadError[K]) => alternative)
      case e @ ReadError.ConversionError(_, _, _) => f.applyOrElse(e, (_: ReadError[K]) => alternative)
      case e @ ReadError.Irrecoverable(list, _)   => f.applyOrElse(e, (_: ReadError[K]) => go(list))
      case e @ ReadError.OrErrors(list, _)        => f.applyOrElse(e, (_: ReadError[K]) => go(list))
      case e @ ReadError.ZipErrors(list, _)       => f.applyOrElse(e, (_: ReadError[K]) => go(list))
      case e @ ReadError.ListErrors(list, _)      => f.applyOrElse(e, (_: ReadError[K]) => go(list))
      case e @ ReadError.MapErrors(list, _)       => f.applyOrElse(e, (_: ReadError[K]) => go(list))
    }
  }

  private[config] def handleDefaultValues[A, B](
    error: ReadError[K],
    config: ConfigDescriptor[A],
    default: B
  ): Either[ReadError[K], AnnotatedRead[B]] = {

    val hasOnlyMissingValuesAndZeroIrrecoverable =
      foldReadError(error)(alternative = false) {
        case ReadError.MissingValue(_, _, _) => true
        case ReadError.Irrecoverable(_, _)   => false
      }(_ && _, true)

    val baseConditionForFallBack =
      hasOnlyMissingValuesAndZeroIrrecoverable && sizeOfZipAndOrErrors(error) == requiredZipAndOrFields(config)

    def hasZeroNonDefaultValues(annotations: Set[AnnotatedRead.Annotation]) =
      !annotations.contains(AnnotatedRead.Annotation.NonDefaultValue)

    error match {
      case MissingValue(_, _, annotations) => Right(AnnotatedRead(default, annotations))

      case ReadError.ZipErrors(_, annotations) if baseConditionForFallBack && hasZeroNonDefaultValues(annotations) =>
        Right(AnnotatedRead(default, annotations))

      case ReadError.OrErrors(_, annotations) if baseConditionForFallBack && hasZeroNonDefaultValues(annotations) =>
        Right(AnnotatedRead(default, annotations))

      case e =>
        Left(Irrecoverable(List(e)))
    }
  }

  private[config] def parseErrorMessage(givenValue: String, expectedType: String) =
    s"Provided value is ${givenValue.toString}, expecting the type ${expectedType}"

  final def requiredZipAndOrFields[A](config: ConfigDescriptor[A]): Int = {
    def loop[B](count: List[K], config: ConfigDescriptor[B]): Int =
      config match {
        case ConfigDescriptorAdt.Lazy(thunk)                => loop(count, thunk())
        case ConfigDescriptorAdt.Zip(left, right)           => loop(count, left) + loop(count, right)
        case ConfigDescriptorAdt.TransformOrFail(cfg, _, _) => loop(count, cfg)
        case ConfigDescriptorAdt.Describe(cfg, _)           => loop(count, cfg)
        case ConfigDescriptorAdt.Nested(_, _, next)         => loop(count, next)
        case ConfigDescriptorAdt.Source(_, _)               => 1
        case ConfigDescriptorAdt.Optional(_)                => 0
        case ConfigDescriptorAdt.OrElse(left, right)        => loop(count, left) + loop(count, right)
        case ConfigDescriptorAdt.OrElseEither(left, right)  => loop(count, left) + loop(count, right)
        case ConfigDescriptorAdt.Default(_, _)              => 0
        case ConfigDescriptorAdt.Sequence(_, _)             => 1
        case ConfigDescriptorAdt.DynamicMap(_, _)           => 1
      }

    loop(Nil, config)
  }

  def sizeOfZipAndOrErrors(error: ReadError[K]): Int =
    foldReadError(error)(0) {
      case ReadError.ListErrors(_, _)         => 1
      case ReadError.MapErrors(_, _)          => 1
      case ReadError.Irrecoverable(_, _)      => 1
      case ReadError.MissingValue(_, _, _)    => 1
      case ReadError.FormatError(_, _, _, _)  => 1
      case ReadError.ConversionError(_, _, _) => 1
    }(_ + _, 0)

}
