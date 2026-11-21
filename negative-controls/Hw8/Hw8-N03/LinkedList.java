/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Singly linked list with a dummy head node and a tail pointer; stores
 * Comparable objects. Insertion sort moves values (not nodes): each new value
 * is placed into the sorted prefix by a front-to-back scan.
 */
public class LinkedList {

    private class ListNode {
        Comparable data;
        ListNode next;

        ListNode(Comparable data, ListNode next) {
            this.data = data;
            this.next = next;
        }
    }

    private final ListNode dummy = new ListNode(null, null);
    private ListNode tail = dummy;
    private int size = 0;

    public void addFirst(Comparable data) {
        dummy.next = new ListNode(data, dummy.next);
        if (tail == dummy) {
            tail = dummy.next;
        }
        size++;
    }

    public void addLast(Comparable data) {
        tail.next = new ListNode(data, null);
        tail = tail.next;
        size++;
    }

    public Comparable getFirst() {
        return size == 0 ? null : dummy.next.data;
    }

    public Comparable removeFirst() {
        if (size == 0) {
            return null;
        }
        ListNode first = dummy.next;
        dummy.next = first.next;
        if (tail == first) {
            tail = dummy;
        }
        size--;
        return first.data;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int getSize() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private static boolean lessThan(Comparable x, Comparable y) {
        return x.compareTo(y) < 0;
    }

    private static LinkedList merge(LinkedList left, LinkedList right) {
        LinkedList merged = new LinkedList();
        while (!left.isEmpty() && !right.isEmpty()) {
            Comparable fa = left.getFirst();
            Comparable fb = right.getFirst();
            if (lessThan(fa, fb)) {
                left.removeFirst();
                merged.addLast(fa);
            } else {
                right.removeFirst();
                merged.addLast(fb);
            }
        }
        while (!left.isEmpty()) {
            merged.addLast(left.removeFirst());
        }
        while (!right.isEmpty()) {
            merged.addLast(right.removeFirst());
        }
        return merged;
    }

    public void MergeSort() {
        Queue queue = new Queue();
        for (ListNode n = dummy.next; n != null; n = n.next) {
            LinkedList one = new LinkedList();
            one.addFirst(n.data);
            queue.enqueue(one);
        }
        while (queue.size() > 1) {
            LinkedList first = (LinkedList) queue.dequeue();
            LinkedList second = (LinkedList) queue.dequeue();
            queue.enqueue(merge(first, second));
        }
        if (queue.size() == 1) {
            LinkedList sorted = (LinkedList) queue.dequeue();
            // rebuild this list from the sorted result, element by element
            dummy.next = null;
            tail = dummy;
            size = 0;
            while (!sorted.isEmpty()) {
                addLast(sorted.removeFirst());
            }
        }
    }

    public void InsertionSort() {
        if (size < 2) {
            return;
        }
        ListNode sortedEnd = dummy.next;
        while (sortedEnd.next != null) {
            ListNode next = sortedEnd.next;
            Comparable value = next.data;
            if (!lessThan(value, sortedEnd.data)) {
                sortedEnd = next; // value already >= the largest sorted value
                continue;
            }
            // shift: walk the sorted prefix and carry the displaced value forward
            ListNode n = dummy.next;
            while (!lessThan(value, n.data)) {
                n = n.next;
            }
            Comparable carry = value;
            while (n != next) {
                Comparable tmp = n.data;
                n.data = carry;
                carry = tmp;
                n = n.next;
            }
            next.data = carry;
            sortedEnd = next;
        }
    }

    public boolean isSorted() {
        ListNode n = dummy.next;
        if (n == null) {
            return true;
        }
        while (n.next != null) {
            if (lessThan(n.next.data, n.data)) {
                return false;
            }
            n = n.next;
        }
        return true;
    }
}
