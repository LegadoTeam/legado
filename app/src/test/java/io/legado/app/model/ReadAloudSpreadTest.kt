package io.legado.app.model
import org.junit.Assert.*
import org.junit.Test
class ReadAloudSpreadTest {
 @Test fun rightColumnFollowsLeftInsteadOfOverlappingIt() {
  val rows = ReadAloudDragSeekSession.readingOrder(listOf(
   ReadAloudDragSeekSession.Target(0,1,10f,30f,0),
   ReadAloudDragSeekSession.Target(0,2,100f,120f,20),
   ReadAloudDragSeekSession.Target(0,3,10f,30f,40)))
  assertTrue(rows[2].top > rows[1].bottom)
  assertEquals(10f,rows[2].screenTop,0f)
  assertEquals(40,ReadAloudDragSeekSession.targetAt(rows,rows[2].top)?.position)
 }
}
