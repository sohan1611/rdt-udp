# Selective Repeat Window Bound



## The rule



With `k` sequence bits, there are `2^k` possible sequence numbers.



For Selective Repeat, the sender window and receiver window are both `W`:



`W + W <= 2^k`



Therefore:



`W <= 2^(k-1)`



The window must not be larger than half of the sequence space. Otherwise, an old packet can become indistinguishable from a new packet after sequence numbers wrap around.



## Failure case: k = 2, W = 3



With `k = 2`, the sequence numbers are:



`0, 1, 2, 3`



An illegal window size is `W = 3`.



1. The sender sends `0, 1, 2`.

2. All three arrive and are delivered.

3. The receiver window moves to `3, 0, 1`.

4. Suppose every ACK is lost.

5. The sender times out and resends its old packet `0`.

6. The receiver sees `0` inside its current window and accepts it as a new packet.

7. That old packet can later be delivered as file data.



This causes incorrect data with no error being reported.



## Legal case: k = 2, W = 2



With the same 2-bit sequence space, `W = 2` is legal.



1. The sender sends `0, 1`.

2. Both are delivered.

3. The receiver window moves to `2, 3`.

4. Suppose every ACK is lost.

5. The sender resends its old packet `0`.

6. `0` belongs to the previous window, so the receiver re-ACKs it instead of delivering it again.



Thus the old packet cannot be mistaken for a new packet.



## Conclusion



For Selective Repeat:



`W <= 2^(k-1)`



This bound prevents old and new packets from becoming ambiguous when sequence numbers wrap around.

