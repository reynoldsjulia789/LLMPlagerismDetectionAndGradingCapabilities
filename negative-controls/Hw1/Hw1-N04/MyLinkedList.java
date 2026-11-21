/*
 * Negative control for HW1 (AI-generated benchmark solution, not student work).
 * EXTRA CREDIT ATTEMPTED: generic version MyLinkedList<E>.
 * Singly linked list with a dummy head node; ListNode is a static nested
 * generic class. MyLinkedListTester was adapted to use MyLinkedList<String>,
 * as the extra-credit rules allow, and produces the same output.
 */
import java.util.NoSuchElementException;

public class MyLinkedList<E> {

	private ListNode<E> head;
	private int size;

	//nested class for ListNode
	private static class ListNode<E> {
		private E data;
		private ListNode<E> next;
		private ListNode(E d) {
			this.data = d;
			this.next = null;
		}
	}

	public MyLinkedList() {
		this.head = new ListNode<>(null); //with a dummy head node
		this.size = 0;
	}

	public int size() {
		return this.size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	// Add Object e to start of this LinkedList
	// Please DO NOT change this addFirst() method.
	// You must keep and include this provided addFirst() method in your source code.
	public void addFirst(E e)
	{
		ListNode<E> node = new ListNode<>(e);
		node.next = head.next;
		head.next = node;
		size++;
	}

	public E removeFirst() throws Exception {
		ListNode<E> victim = head.next;
		if (victim == null) {
			throw new Exception("Exception: LinkedList is empty!");
		}
		head.next = victim.next;
		victim.next = null;
		size--;
		return victim.data;
	}

	public boolean contains(Object o) {
		for (ListNode<E> walk = head.next; walk != null; walk = walk.next) {
			boolean hit = (o == null) ? (walk.data == null) : o.equals(walk.data);
			if (hit) {
				return true;
			}
		}
		return false;
	}

	public boolean remove(Object o) {
		for (ListNode<E> trail = head; trail.next != null; trail = trail.next) {
			E candidate = trail.next.data;
			boolean hit = (o == null) ? (candidate == null) : o.equals(candidate);
			if (hit) {
				trail.next = trail.next.next;
				size--;
				return true;
			}
		}
		return false;
	}

	public boolean removeAllCopies(Object o) {
		// rebuild the chain, keeping only nodes that do not match
		ListNode<E> keptTail = head;
		ListNode<E> walk = head.next;
		int removed = 0;
		while (walk != null) {
			ListNode<E> after = walk.next;
			boolean hit = (o == null) ? (walk.data == null) : o.equals(walk.data);
			if (hit) {
				removed++;
			} else {
				keptTail.next = walk;
				keptTail = walk;
			}
			walk = after;
		}
		keptTail.next = null;
		size -= removed;
		return removed > 0;
	}

	public static <T> MyLinkedList<T> interleave(MyLinkedList<T> A, MyLinkedList<T> B) {
		MyLinkedList<T> out = new MyLinkedList<>();
		ListNode<T> outTail = out.head;
		ListNode<T> pa = A.head.next;
		ListNode<T> pb = B.head.next;
		boolean takeFromA = true;
		while (pa != null || pb != null) {
			ListNode<T> source;
			if ((takeFromA && pa != null) || pb == null) {
				source = pa;
				pa = pa.next;
			} else {
				source = pb;
				pb = pb.next;
			}
			outTail.next = new ListNode<>(source.data); // copy, A and B stay unchanged
			outTail = outTail.next;
			out.size++;
			takeFromA = !takeFromA;
		}
		return out;
	}

	public void add(int index, E o) {
		if (index < 0 || index > size) {
			throw new IndexOutOfBoundsException("Index Passed in not valid!");
		}
		ListNode<E> anchor = head;
		int position = 0;
		while (position < index) {
			anchor = anchor.next;
			position++;
		}
		ListNode<E> made = new ListNode<>(o);
		made.next = anchor.next;
		anchor.next = made;
		size++;
	}

	public E get(int index) throws IndexOutOfBoundsException {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
		ListNode<E> walk = head.next;
		int position = 0;
		while (position < index) {
			walk = walk.next;
			position++;
		}
		return walk.data;
	}

	public E remove(int index) throws IndexOutOfBoundsException {
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Provided index is out of bounds! " + index);
		}
		ListNode<E> anchor = head;
		for (int position = 0; position < index; position++) {
			anchor = anchor.next;
		}
		E value = anchor.next.data;
		anchor.next = anchor.next.next;
		size--;
		return value;
	}

	public boolean add(E e) {
		ListNode<E> end = head;
		while (end.next != null) {
			end = end.next;
		}
		end.next = new ListNode<>(e);
		size++;
		return true;
	}

        //Please DO NOT Change the following toString() method!!!
        //You must include the following toString() method in your source code.
	@Override
	public String toString() {
		String result = "{";
	    for (ListNode<E> node = this.head.next; node != null; node = node.next) {
	    		if(node.next != null)
	    			result += node.data + "->";
	    		else
	    			result += node.data;
	    }
	    return result + "}";
	  }
}
