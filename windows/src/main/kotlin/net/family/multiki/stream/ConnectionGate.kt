package net.family.multiki.stream

/**
 * The archive allows ONE connection per IP in each section.
 * Every network transfer to a section's file server must hold that section's lane.
 *
 * - Only one owner per lane at any moment.
 * - A new owner preempts the current one and waits until it has really closed its connection.
 * - Between two connections to the same lane there is a small pause, so the server never sees
 *   an overlap (a closing socket and a new one at the same time).
 */
object ConnectionGate {
    interface Owner {
        /** Stop the transfer as soon as possible and release the lane. */
        fun preempt()
    }

    private const val MIN_GAP_MS = 800L
    private val lock = Object()
    private val holders = HashMap<String, Owner>()
    private val lastRelease = HashMap<String, Long>()

    /** Blocks until [owner] holds [lane]. */
    fun acquire(lane: String, owner: Owner) {
        val gapUntil: Long
        synchronized(lock) {
            while (true) {
                val current = holders[lane]
                if (current == null || current === owner) break
                current.preempt()
                lock.wait(200)
            }
            holders[lane] = owner
            gapUntil = (lastRelease[lane] ?: 0L) + MIN_GAP_MS
        }
        val wait = gapUntil - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
    }

    /**
     * Background work (filling the cache) must not push away someone who is watching:
     * takes the lane only if it is free. Returns false if busy.
     */
    fun tryAcquire(lane: String, owner: Owner): Boolean {
        val gapUntil: Long
        synchronized(lock) {
            val current = holders[lane]
            if (current != null && current !== owner) return false
            holders[lane] = owner
            gapUntil = (lastRelease[lane] ?: 0L) + MIN_GAP_MS
        }
        val wait = gapUntil - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        return true
    }

    fun release(lane: String, owner: Owner) {
        synchronized(lock) {
            if (holders[lane] === owner) {
                holders.remove(lane)
                lastRelease[lane] = System.currentTimeMillis()
            }
            lock.notifyAll()
        }
    }

    fun isBusy(lane: String): Boolean = synchronized(lock) { holders[lane] != null }
}
