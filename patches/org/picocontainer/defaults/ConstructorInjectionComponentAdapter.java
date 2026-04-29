package org.picocontainer.defaults;

import org.picocontainer.ComponentMonitor;
import org.picocontainer.Parameter;
import org.picocontainer.PicoContainer;
import org.picocontainer.PicoIntrospectionException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class ConstructorInjectionComponentAdapter extends InstantiatingComponentAdapter {

    public ConstructorInjectionComponentAdapter(Object componentKey, Class componentImplementation,
            Parameter[] parameters, boolean allowNonPublicClasses,
            ComponentMonitor componentMonitor, LifecycleStrategy lifecycleStrategy)
            throws AssignabilityRegistrationException, NotConcreteRegistrationException {
        super(componentKey, componentImplementation, parameters, allowNonPublicClasses,
                componentMonitor, lifecycleStrategy);
    }

    public ConstructorInjectionComponentAdapter(Object componentKey, Class componentImplementation,
            Parameter[] parameters, boolean allowNonPublicClasses)
            throws AssignabilityRegistrationException, NotConcreteRegistrationException {
        super(componentKey, componentImplementation, parameters, allowNonPublicClasses,
                new DelegatingComponentMonitor(), new DefaultLifecycleStrategy(new DelegatingComponentMonitor()));
    }

    public ConstructorInjectionComponentAdapter(Object componentKey, Class componentImplementation)
            throws AssignabilityRegistrationException, NotConcreteRegistrationException {
        this(componentKey, componentImplementation, null, false);
    }

    @Override
    protected Constructor getGreediestSatisfiableConstructor(PicoContainer container)
            throws PicoIntrospectionException, UnsatisfiableDependenciesException,
            AmbiguousComponentResolutionException, AssignabilityRegistrationException,
            NotConcreteRegistrationException {
        Constructor[] constructors = getComponentImplementation().getDeclaredConstructors();
        List<Constructor> sortable = new ArrayList<Constructor>(Arrays.asList(constructors));
        Collections.sort(sortable, new Comparator<Constructor>() {
            public int compare(Constructor a, Constructor b) {
                return b.getParameterTypes().length - a.getParameterTypes().length;
            }
        });
        for (Constructor constructor : sortable) {
            try {
                Class[] paramTypes = constructor.getParameterTypes();
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    Parameter param = parameters != null && i < parameters.length
                            ? parameters[i] : ComponentParameter.DEFAULT;
                    Object resolved = param.resolveInstance(container, this, paramTypes[i]);
                    if (resolved == null && !paramTypes[i].isPrimitive()) {
                        args = null;
                        break;
                    }
                    args[i] = resolved;
                }
                if (args != null) return constructor;
            } catch (Exception ignored) {
            }
        }
        if (!sortable.isEmpty()) return sortable.get(0);
        throw new UnsatisfiableDependenciesException(this, null, null, container);
    }

    @Override
    public Object getComponentInstance(PicoContainer container) {
        Constructor constructor;
        try {
            constructor = getGreediestSatisfiableConstructor(container);
        } catch (Exception e) {
            throw new PicoInvocationTargetInitializationException(e);
        }
        Class[] paramTypes = constructor.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Parameter param = parameters != null && i < parameters.length
                    ? parameters[i] : ComponentParameter.DEFAULT;
            args[i] = param.resolveInstance(container, this, paramTypes[i]);
        }
        try {
            return newInstance(constructor, args);
        } catch (InstantiationException e) {
            throw new PicoInvocationTargetInitializationException(e);
        } catch (IllegalAccessException e) {
            throw new PicoInvocationTargetInitializationException(e);
        } catch (InvocationTargetException e) {
            throw new PicoInvocationTargetInitializationException(e.getCause());
        }
    }

    public void verify(PicoContainer container) {}
}
