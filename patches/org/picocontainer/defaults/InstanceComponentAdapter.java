package org.picocontainer.defaults;

import org.picocontainer.PicoContainer;

public class InstanceComponentAdapter extends AbstractComponentAdapter implements LifecycleStrategy {
  private Object componentInstance;
  private LifecycleStrategy lifecycleStrategy;

  public InstanceComponentAdapter(Object componentKey, Object componentInstance)
      throws AssignabilityRegistrationException, NotConcreteRegistrationException {
    this(componentKey, componentInstance, null);
  }

  public InstanceComponentAdapter(Object componentKey, Object componentInstance, LifecycleStrategy lifecycleStrategy)
      throws AssignabilityRegistrationException, NotConcreteRegistrationException {
    super(componentKey, getInstanceClass(componentInstance));
    this.componentInstance = componentInstance;
    this.lifecycleStrategy = lifecycleStrategy;
  }

  private static Class getInstanceClass(Object componentInstance) {
    return componentInstance.getClass();
  }

  @Override
  public Object getComponentInstance(PicoContainer container) {
    return componentInstance;
  }

  public void verify(PicoContainer container) {}
}
