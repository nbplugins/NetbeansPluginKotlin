package org.picocontainer;

public interface MutablePicoContainer extends PicoContainer {
  ComponentAdapter registerComponentImplementation(Object componentKey, Class componentImplementation);

  ComponentAdapter registerComponentInstance(Object componentKey, Object componentInstance);

  default ComponentAdapter registerComponentInstance(Object componentInstance) {
    return registerComponentInstance(componentInstance.getClass(), componentInstance);
  }

  ComponentAdapter unregisterComponent(Object componentKey);
}
