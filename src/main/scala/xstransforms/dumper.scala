package xstransforms

import chisel3.experimental.{ChiselAnnotation, RunFirrtlTransform, annotate}
import firrtl.annotations.Annotation
import firrtl.transforms.{DumperAnnotation, DumperTransform, Flatten, FlattenAnnotation}

object Dumper {
  def dump(component: chisel3.Module): Unit = {
    annotate(new ChiselAnnotation with RunFirrtlTransform {
      def toFirrtl: Annotation = DumperAnnotation(component.toTarget)
      def transformClass = classOf[DumperTransform]
    })
    annotate(new ChiselAnnotation with RunFirrtlTransform {
      def toFirrtl: Annotation = FlattenAnnotation(component.toNamed)
      def transformClass = classOf[Flatten]
    })
  }
}
