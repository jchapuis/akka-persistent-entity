package example.domain

import java.time.Instant

trait TimestampProvider {
  def timestamp: Instant
}
