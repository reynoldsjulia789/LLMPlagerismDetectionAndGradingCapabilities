/*
 * Negative control for HW8 (AI-generated benchmark solution, not student work).
 * Generic doubly linked list with a sentinel node (sentinel.prev is the tail,
 * so addLast is O(1)). Elements must be Comparable.
 */
public class LinkedList<T extends Comparable<T>> {

    private final class Node {
        T item;
        Node prev;
        Node next;

        Node(T item) {
            this.item = item;
        }
    }

    private final Node sentinel = new Node(null);
    private int length;

    public LinkedList() {
        sentinel.next = sentinel;
        sentinel.prev = sentinel;
    }

    private void linkBetween(Node before, Node node, Node after) {
        node.prev = before;
        node.next = after;
        before.next = node;
        after.prev = node;
    }

    private void unlink(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    public void addFirst(T item) {
        linkBetween(sentinel, new Node(item), sentinel.next);
        length++;
    }

    public void addLast(T item) {
        linkBetween(sentinel.prev, new Node(item), sentinel);
        length++;
    }

    public T getFirst() {
        if (isEmpty()) {
            throw new java.util.NoSuchElementException();
        }
        return sentinel.next.item;
    }

    public T removeFirst() {
        T item = getFirst();
        unlink(sentinel.next);
        length--;
        return item;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public int getSize() {
        return length;
    }

    public LinkedList<T> merge(LinkedList<T> a, LinkedList<T> b) {
        LinkedList<T> s = new LinkedList<>();
        while (!a.isEmpty() && !b.isEmpty()) {
            if (a.getFirst().compareTo(b.getFirst()) < 0) {
                s.addLast(a.removeFirst());
            } else {
                s.addLast(b.removeFirst());
            }
        }
        while (!a.isEmpty()) {
            s.addLast(a.removeFirst());
        }
        while (!b.isEmpty()) {
            s.addLast(b.removeFirst());
        }
        return s;
    }

    public void MergeSort() {
        Queue<LinkedList<T>> q = new Queue<>();
        for (Node n = sentinel.next; n != sentinel; n = n.next) {
            LinkedList<T> newList = new LinkedList<>();
            newList.addFirst(n.item);
            q.enqueue(newList);
        }
        while (q.size() > 1) {
            LinkedList<T> sublist1 = q.dequeue();
            LinkedList<T> sublist2 = q.dequeue();
            q.enqueue(merge(sublist1, sublist2));
        }
        if (q.size() == 1) {
            LinkedList<T> result = q.dequeue();
            // splice the result's nodes into this list's sentinel
            if (result.isEmpty()) {
                return;
            }
            Node first = result.sentinel.next;
            Node last = result.sentinel.prev;
            sentinel.next = first;
            first.prev = sentinel;
            sentinel.prev = last;
            last.next = sentinel;
            length = result.length;
        }
    }

    public void InsertionSort() {
        Node current = sentinel.next.next;
        while (current != sentinel) {
            Node following = current.next;
            Node target = current.prev;
            while (target != sentinel && target.item.compareTo(current.item) > 0) {
                target = target.prev;
            }
            if (target != current.prev) {
                unlink(current);
                linkBetween(target, current, target.next);
            }
            current = following;
        }
    }

    public boolean isSorted() {
        Node n = sentinel.next;
        while (n != sentinel && n.next != sentinel) {
            if (n.item.compareTo(n.next.item) > 0) {
                return false;
            }
            n = n.next;
        }
        return true;
    }
}
