/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * LinkedList: singly linked list of Integers with head and tail references,
 * so addLast() is O(1). Provides the queue-based MergeSort(), InsertionSort()
 * and an O(n) isSorted().
 */
public class LinkedList {

    private static class Node {
        int value;
        Node next;

        Node(int value) {
            this.value = value;
        }
    }

    private Node head;
    private Node tail;
    private int size;

    public void addFirst(int value) {
        Node n = new Node(value);
        n.next = head;
        head = n;
        if (tail == null) {
            tail = n;
        }
        size++;
    }

    public void addLast(int value) {
        Node n = new Node(value);
        if (tail == null) {
            head = n;
        } else {
            tail.next = n;
        }
        tail = n;
        size++;
    }

    public int getFirst() {
        if (head == null) {
            throw new IllegalStateException("list is empty");
        }
        return head.value;
    }

    public int removeFirst() {
        int v = getFirst();
        head = head.next;
        if (head == null) {
            tail = null;
        }
        size--;
        return v;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int getSize() {
        return size;
    }

    /** Merges two sorted lists into a new sorted list, emptying A and B. */
    private static LinkedList merge(LinkedList A, LinkedList B) {
        LinkedList S = new LinkedList();
        while (!A.isEmpty() && !B.isEmpty()) {
            int fa = A.getFirst();
            int fb = B.getFirst();
            if (fa < fb) {
                A.removeFirst();
                S.addLast(fa);
            } else {
                B.removeFirst();
                S.addLast(fb);
            }
        }
        while (!A.isEmpty()) {
            S.addLast(A.getFirst());
            A.removeFirst();
        }
        while (!B.isEmpty()) {
            S.addLast(B.getFirst());
            B.removeFirst();
        }
        return S;
    }

    public void MergeSort() {
        Queue q = new Queue();
        for (Node n = head; n != null; n = n.next) {
            LinkedList single = new LinkedList();
            single.addFirst(n.value);
            q.enqueue(single);
        }
        while (q.size() > 1) {
            LinkedList sublist1 = q.dequeue();
            LinkedList sublist2 = q.dequeue();
            q.enqueue(merge(sublist1, sublist2));
        }
        if (!q.isEmpty()) {
            // the sorted list is a different object: adopt its nodes
            LinkedList sorted = q.dequeue();
            this.head = sorted.head;
            this.tail = sorted.tail;
            this.size = sorted.size;
        }
    }

    public void InsertionSort() {
        // take nodes off the front one at a time and insert them in order into a new chain
        Node sortedHead = null;
        Node sortedTail = null;
        Node pending = head;
        while (pending != null) {
            Node node = pending;
            pending = pending.next;
            node.next = null;
            if (sortedHead == null || node.value < sortedHead.value) {
                node.next = sortedHead;
                sortedHead = node;
                if (sortedTail == null) {
                    sortedTail = node;
                }
            } else if (node.value >= sortedTail.value) {
                sortedTail.next = node; // common case for already-ordered runs
                sortedTail = node;
            } else {
                Node before = sortedHead;
                while (before.next != null && before.next.value <= node.value) {
                    before = before.next;
                }
                node.next = before.next;
                before.next = node;
            }
        }
        head = sortedHead;
        tail = sortedTail;
    }

    public boolean isSorted() {
        for (Node n = head; n != null && n.next != null; n = n.next) {
            if (n.value > n.next.value) {
                return false;
            }
        }
        return true;
    }
}
