import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class Hw2ListAdapter
{
    private final Object   _object;
    private final Class<?> _class;

    private static final String[] CANDIDATE_CLASSES = {
            "CDoublyLinkedList",
            "CircularDoublyLinkedList",
            "CircularlyDoublyLinkedList",
            "CircularLinkedList",
            "CircularlyLinkedList",
            "DoublyLinkedList",
            "MyLinkedList",
            "LinkedList"
    };

    public Hw2ListAdapter()
    {
        Class<?> found = null;

        for (String name : CANDIDATE_CLASSES)
        {
            try
            {
                found = Class.forName(name);
                break;
            }
            catch (ClassNotFoundException ignored) {}
        }

        if (found == null)
        {
            throw new RuntimeException("No valid List class found in submission.");
        }

        this._class = found;

        try
        {
            this._object = _class.getDeclaredConstructor().newInstance();
        }
        catch (Exception caught)
        {
            throw new RuntimeException("Failed to instantiate " + _class.getName(), caught);
        }
    }

    // wrap the returned list from subListOfSmallerValues()
    private Hw2ListAdapter(Object existingInstance)
    {
        if (existingInstance == null)
        {
            throw new RuntimeException("Returned list instance was null.");
        }

        this._object = existingInstance;
        this._class  = existingInstance.getClass();
    }

    private Method findMethod(String name, Class<?>... paramTypes)
    {
        for (Method m : _class.getMethods())
        {
            if (m.getName().equalsIgnoreCase(name) && Arrays.equals(m.getParameterTypes(), paramTypes))
            {
                return m;
            }
        }
        throw new RuntimeException("Method '" + name + "' with matching parameters not found in " + _class.getName());
    }

    private Object invokeUnchecked(String name, Class<?>[] paramTypes, Object... args)
    {
        try
        {
            Method m = findMethod(name, paramTypes);
            return m.invoke(_object, args);
        }
        catch (InvocationTargetException caught)
        {
            Throwable cause = caught.getCause();

            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;

            throw new RuntimeException(cause);
        }
        catch (Exception caught)
        {
            throw new RuntimeException(caught);
        }
    }

    public boolean isEmpty()
    {
        return (boolean) invokeUnchecked("isEmpty", new Class<?>[]{});
    }

    public void addFirst(Object data)
    {
        invokeUnchecked("addFirst", new Class<?>[]{Object.class}, data);
    }

    public void addLast(Object data)
    {
        invokeUnchecked("addLast", new Class<?>[]{Object.class}, data);
    }

    public Hw2ListAdapter subListOfSmallerValues(Comparable<?> data)
    {
        Object result = invokeUnchecked("subListOfSmallerValues", new Class<?>[]{Comparable.class}, data);

        return new Hw2ListAdapter(result);
    }

    public boolean removeStartingAtBack(Object dataToRemove)
    {
        return (boolean) invokeUnchecked("removeStartingAtBack", new Class<?>[]{Object.class}, dataToRemove);
    }

    public int lastIndexOf(Object o)
    {
        return (int) invokeUnchecked("lastIndexOf", new Class<?>[]{Object.class}, o);
    }

    public boolean retainAll(Hw2ListAdapter other)
    {
        Object otherInstance = (other == null) ? null : other._object;

        return (boolean) invokeUnchecked("retainAll", new Class<?>[]{this._class}, otherInstance);
    }

    public void insertionSort()
    {
        invokeUnchecked("insertionSort", new Class<?>[]{});
    }

    @Override
    public String toString()
    {
        return _object.toString();
    }
}
