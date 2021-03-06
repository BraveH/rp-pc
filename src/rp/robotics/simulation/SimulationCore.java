package rp.robotics.simulation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import lejos.util.Delay;
import rp.util.Pair;
import rp.util.Rate;

/**
 * Class to manage the core components of the simulation to ensure they run in
 * sync. All steppables are run in the same thread, minimising the damage thread
 * contention can do to the simulation.
 * 
 * @author Nick Hawes
 *
 */
public class SimulationCore extends Thread {

	private class SteppableWrapper {

		private final int m_stepRatio;
		private final ArrayList<SimulationSteppable> m_steppables;
		private int countDown;
		private Instant lastCall = null;

		public SteppableWrapper(int _stepRatio, SimulationSteppable _steppable) {
			super();
			m_stepRatio = _stepRatio;
			m_steppables = new ArrayList<SimulationSteppable>();
			m_steppables.add(_steppable);
			countDown = m_stepRatio;
		}

		public void step(Instant _now) {

			if (lastCall == null) {
				lastCall = _now;
			}

			if (--countDown == 0) {

				// System.out.println("GO ------ " + m_stepRatio);

				Duration interval = Duration.between(lastCall, _now);

				for (Iterator<SimulationSteppable> iterator = m_steppables
						.iterator(); iterator.hasNext();) {
					SimulationSteppable steppable = iterator.next();
					if (steppable.remove(_now, interval)) {

						iterator.remove();
						synchronized (steppable) {
							steppable.notifyAll();
						}

					} else {
						// System.out.println("wrapper step");
						steppable.step(_now, interval);
						// if (steppable.remove(_now, interval)) {
						//
						// iterator.remove();
						// synchronized (steppable) {
						// steppable.notifyAll();
						// }
						//
						// }

					}

				}

				lastCall = _now;
				countDown = m_stepRatio;
			}
		}
	}

	private final LinkedList<SteppableWrapper> m_wrappers = new LinkedList<SteppableWrapper>();
	private final ConcurrentLinkedQueue<Pair<SimulationSteppable, Integer>> m_toAdd = new ConcurrentLinkedQueue<>();

	public static SimulationCore createSimulationCore() {
		return new SimulationCore(true);
	}

	public static SimulationCore createSimulationCore(boolean _start) {
		return new SimulationCore(_start);
	}

	private final double m_targetRate;
	private boolean m_inStep = false;
	private boolean m_paused = false;

	private SimulationCore(boolean _start) {
		setDaemon(true);
		setPriority(MAX_PRIORITY);
		m_targetRate = 60;
		if (_start) {
			start();
		}
	}

	public double getSimulationRate() {
		return m_targetRate;
	}

	/**
	 * 
	 * Add a steppable entity to the simulation that runs at a configurable rate
	 * compared with the base rate.
	 * 
	 * @param _steppable
	 * @param _stepRate
	 *            The multiple of the target rate to run at. 1 runs every
	 *            simulation step, 2 runs every other step etc.
	 */
	private void addSteppable(SimulationSteppable _steppable, int _stepRate) {
		m_toAdd.add(Pair.makePair(_steppable, _stepRate));
	}

	private void addSteppablesFromQueue() {

		Pair<SimulationSteppable, Integer> pair = m_toAdd.poll();

		// int in = 0;

		while (pair != null) {

			// System.out.println("Adding: " + ++in);

			SimulationSteppable _steppable = pair.getItem1();
			Integer _stepRate = pair.getItem2();

			if (_stepRate < 1) {
				System.out.println("Invalid step rate");
				_stepRate = 1;
			}

			int count = 0;
			boolean stored = false;
			for (SteppableWrapper wrapper : m_wrappers) {

				// System.out.println("c: " + count);
				// System.out.println("step: " + _stepRate);
				// System.out.println("wrap: " + wrapper.m_stepRatio);

				if (_stepRate < wrapper.m_stepRatio) {
					m_wrappers.add(count, new SteppableWrapper(_stepRate,
							_steppable));
					stored = true;
					// System.out.println("Added new");
					break;
				} else if (_stepRate == wrapper.m_stepRatio) {
					wrapper.m_steppables.add(_steppable);
					stored = true;
					// System.out.println("Udpated");
					break;
				} else {
					count++;
				}

			}
			if (!stored) {
				// System.out.println("At end");
				// if we got to the end of the list, add it there
				m_wrappers.add(count, new SteppableWrapper(_stepRate,
						_steppable));
			}
			pair = m_toAdd.poll();
		}
	}

	public void addSteppable(SimulationSteppable _steppable) {
		addSteppable(_steppable, 1);
	}

	private final Lock m_stepLock = new ReentrantLock();

	public void pause() {
		synchronized (m_stepLock) {
			if (!m_paused) {
				m_stepLock.lock();
				m_paused = true;
			}
		}
	}

	public void unpause() {
		synchronized (m_stepLock) {
			if (m_paused) {
				m_stepLock.unlock();
				m_paused = false;
			}
		}
	}

	@Override
	public void run() {
		Rate r = new Rate(m_targetRate);

		Duration step = Duration.ofMillis((long) (1000.0 / m_targetRate));

		Instant now = Instant.now();

		while (true) {

			m_stepLock.lock();

			try {

				m_inStep = true;

				addSteppablesFromQueue();

				now = now.plus(step);

				for (SteppableWrapper wrapper : m_wrappers) {
					wrapper.step(now);
				}

			} catch (Exception e) {
				System.err.println("caught in SimulationCore.run(): "
						+ e.getMessage());
				e.printStackTrace();
			} finally {

				m_inStep = false;

				m_stepLock.unlock();

				synchronized (m_stepLock) {
					m_stepLock.notifyAll();
				}
			}

			r.sleep();
			Delay.msDelay((long) (1000.0 / m_targetRate));

		}
	}

	public void waitForEndOfStep() {
		synchronized (m_stepLock) {
			while (m_inStep) {
				try {
					m_stepLock.wait();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Wait until the current cycle of steps is completed.
	 * 
	 * @param _steppable
	 */
	public void addAndWaitSteppable(SimulationSteppable _steppable) {
		addAndWaitSteppable(_steppable, 1);
	}

	/**
	 * Adds a steppable to the simulation then waits until it is removed again.
	 * 
	 * @param _steppable
	 * @param _stepRate
	 */
	public void addAndWaitSteppable(SimulationSteppable _steppable,
			int _stepRate) {

		addSteppable(_steppable, _stepRate);

		waitSteppable(_steppable);
	}

	public void waitSteppable(SimulationSteppable _steppable) {
		boolean except = false;
		// while (!_steppable.remove()) {
		// if (except) {
		// System.out.println("restart loop");
		// }

		synchronized (_steppable) {
			try {
				// wait on a loop as it helps deal with sync errors
				_steppable.wait();
			} catch (InterruptedException e) {
				except = true;
				System.out
						.println("caught in waitSteppable: " + e.getMessage());
				System.out.println("caught in waitSteppable: "
						+ _steppable.getClass());

				e.printStackTrace();
				return;
			}
		}
		// }
		if (except) {
			System.out.println("done wait");
		}
	}
}
