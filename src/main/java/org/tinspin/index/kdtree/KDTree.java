/*
 * Copyright 2016-2017 Tilmann Zaeschke
 * 
 * This file is part of TinSpin.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.tinspin.index.kdtree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.tinspin.index.PointEntry;
import org.tinspin.index.PointEntryDist;
import org.tinspin.index.PointIndex;
import org.tinspin.index.QueryIterator;
import org.tinspin.index.QueryIteratorKNN;

/**
 * A simple KD-Tree implementation. 
 * 
 * @author ztilmann
 *
 * @param <T>
 */
public class KDTree<T> implements PointIndex<T> {

	private static final String NL = System.lineSeparator();

	public static final boolean DEBUG = false;
	
	private final int dims;
	private int size = 0; 
	private int modCount = 0;
	//During insertion, the tree maintains an invariant that if two points have the
	//same value in any dimension, then one key is never in the 'lower' branch of the other.
	//This allows very efficient look-up because we have to follow only a single path.
	//Unfortunately, removing keys (and moving up keys in the tree) may break this invariant.
	//
	//The cost of breaking the invariant is that there may be two branches that contain the same
	//key. This makes searches more expensive simply because the code gets more complex.
	//
	//One solution to maintain the invariant is to repair the invariant by moving all points 
	//with the same value into the 'upper' part of the point that was moved up. 
	//Identifying these points is relatively cheap, we can do this while searching for 
	//the min/max during removal. However, _moving_ these point may be very expensive.
	//
	//Our (pragmatic) solution is identify when the invariant gets broken.
	//When it is not broken, we use the simple search. If it gets broken, we use the slower search.
	//This is especially useful in scenarios where 'remove()' is not required or where
	//points have never the same values (such as for physical measurements or other experimental results).
	private boolean invariantBroken = false;
	
	private Node<T> root;
	
	public static void main(String ... args) {
		for (int i = 0; i < 10; i++) {
			try {
				test(i);
			} catch(Exception e) {
				System.out.println("Failed with r=" + i);
				throw new RuntimeException(e);
			}
		}
	}
	
	private static void test(int r) {
//		double[][] point_list = {{2,3}, {5,4}, {9,6}, {4,7}, {8,1}, {7,2}};
		double[][] point_list = new double[500000][14];
		Random R = new Random(r);
		for (double[] p : point_list) {
			Arrays.setAll(p, (i) -> { return (double)R.nextInt(100);} );
		}
		KDTree<double[]> tree = create(point_list[0].length);
		for (double[] data : point_list) {
			tree.insert(data, data);
		}
//	    System.out.println(tree.toStringTree());
		for (double[] key : point_list) {
			if (!tree.containsExact(key)) {
				throw new IllegalStateException("" + Arrays.toString(key));
			}
		}
		for (double[] key : point_list) {
			System.out.println(Arrays.toString(tree.queryExact(key)));
		}
//	    System.out.println(tree.toStringTree());
	    
		for (double[] key : point_list) {
//			System.out.println(tree.toStringTree());
			System.out.println("kNN query: " + Arrays.toString(key));
			QueryIteratorKNN<PointEntryDist<double[]>> iter = tree.queryKNN(key, 1);
			if (!iter.hasNext()) {
				throw new IllegalStateException("kNN() failed: " + Arrays.toString(key));
			}
			double[] answer = iter.next().point();
			if (answer != key && !Arrays.equals(answer, key)) {
				throw new IllegalStateException("Expected " + Arrays.toString(key) + " but got " + Arrays.toString(answer));
			}
		}

		for (double[] key : point_list) {
//			System.out.println(tree.toStringTree());
			System.out.println("Removing: " + Arrays.toString(key));
			if (!tree.containsExact(key)) {
				throw new IllegalStateException("containsExact() failed: " + Arrays.toString(key));
			}
			double[] answer = tree.remove(key); 
			if (answer != key && !Arrays.equals(answer, key)) {
				throw new IllegalStateException("Expected " + Arrays.toString(key) + " but got " + Arrays.toString(answer));
			}
		}
	}
	
	private KDTree(int dims) {
		if (DEBUG) {
			System.err.println("Warning: DEBUG enabled");
		}
		this.dims = dims;
	}

	public static <T> KDTree<T> create(int dims) {
		return new KDTree<>(dims);
	}
	
	/**
	 * Insert a key-value pair.
	 * @param key the key
	 * @param value the value
	 */
	@Override
	public void insert(double[] key, T value) {
		size++;
		modCount++;
		if (root == null) {
			root = new Node<>(key, value, 0);
			return;
		}
		Node<T> n = root;
		while ((n = n.getClosestNodeOrAddPoint(key, value, dims)) != null);
	}
	
	/**
	 * Check whether a given key exists.
	 * @param key the key to check
	 * @return true iff the key exists
	 */
	public boolean containsExact(double[] key) {
		return findNodeExcat(key, new RemoveResult<>()) != null;
	}
	
	/**
	 * Get the value associates with the key.
	 * @param key the key to look up
	 * @return the value for the key or 'null' if the key was not found
	 */
	@Override
	public T queryExact(double[] key) {
		Node<T> e = findNodeExcat(key, new RemoveResult<>());
		return e == null ? null : e.getValue();
	}
	
	private Node<T> findNodeExcat(double[] key, RemoveResult<T> resultDepth) {
		if (root == null) {
			return null;
		}
		return invariantBroken 
				? findNodeExactSlow(key, root, null, resultDepth) 
						: findNodeExcatFast(key, null, resultDepth);
	} 

	private Node<T> findNodeExcatFast(double[] key, Node<T> parent, RemoveResult<T> resultDepth) {
		Node<T> n = root;
		do {
			double[] nodeKey = n.getKey();
			double nodeX = nodeKey[n.getDim()];
			double keyX = key[n.getDim()];
			if (keyX == nodeX && Arrays.equals(key, nodeKey)) {
				resultDepth.pos = n.getDim();
				resultDepth.nodeParent = parent;
				return n;
			}
			parent = n;
			n = (keyX >= nodeX) ? n.getHi() : n.getLo();
		} while (n != null);
		return n;
	}
	
	private Node<T> findNodeExactSlow(double[] key, Node<T> n, Node<T> parent, RemoveResult<T> resultDepth) {
		do {
			double[] nodeKey = n.getKey();
			double nodeX = nodeKey[n.getDim()];
			double keyX = key[n.getDim()];
			if (keyX == nodeX) {
				if (Arrays.equals(key, nodeKey)) {
					resultDepth.pos = n.getDim();
					resultDepth.nodeParent = parent;
					return n;
				}
				//Broken invariant? We need to check the 'lower' part as well...
				if (n.getLo() != null) {
					Node<T> n2 = findNodeExactSlow(key, n.getLo(), parent, resultDepth);
					if (n2 != null) {
						return n2;
					}
				}
			}
			parent = n;
			n = (keyX >= nodeX) ? n.getHi() : n.getLo();
		} while (n != null);
		return n;
	}
	
	/**
	 * Remove a key.
	 * @param key key to remove
	 * @return the value associated with the key or 'null' if the key was not found
	 */
	@Override
	public T remove(double[] key) {
		if (root == null) {
			return null;
		}
		
		//find
		RemoveResult<T> removeResult = new RemoveResult<>();
		Node<T> eToRemove = findNodeExcat(key, removeResult);
		if (eToRemove == null) {
			return null;
		}

		//remove
		modCount++;
		T value = eToRemove.getValue();
		if (eToRemove == root && size == 1) {
			root = null;
			size = 0;
			invariantBroken = false;
		}
		
		//find replacement
		removeResult.nodeParent = null; 
		while (eToRemove != null && !eToRemove.isLeaf()) {
			//recurse
			int pos = removeResult.pos;
			removeResult.node = null; 
			//randomize search direction (modCount)
			if (((modCount & 0x1) == 0 || eToRemove.getHi() == null) && eToRemove.getLo() != null) {
				//get replacement from left
				removeResult.best = Double.NEGATIVE_INFINITY;
				removeMaxLeaf(eToRemove.getLo(), eToRemove, pos, removeResult);
			} else if (eToRemove.getHi() != null) {
				//get replacement from right
				removeResult.best = Double.POSITIVE_INFINITY;
				removeMinLeaf(eToRemove.getHi(), eToRemove, pos, removeResult);
			}
			eToRemove.setKeyValue(removeResult.node.getKey(), removeResult.node.getValue());
			eToRemove = removeResult.node;
		} 
		//leaf node
		Node<T> parent = removeResult.nodeParent; 
		if (parent != null) {
			if (parent.getLo() == eToRemove) {
				parent.setLeft(null);
			} else if (parent.getHi() == eToRemove) {
				parent.setRight(null);
			} else { 
				throw new IllegalStateException();
			}
		}
		size--;
		return value;
	}

	private static class RemoveResult<T> {
		Node<T> node = null;
		Node<T> nodeParent = null;
		double best;
		int pos;
	}
	
	private void removeMinLeaf(Node<T> node, Node<T> parent, int pos, RemoveResult<T> result) {
		//Split in 'interesting' dimension
		double localX = node.getKey()[pos];
		if (pos == node.getDim()) {
			//We strictly look for leaf nodes with left==null
			if (node.getLo() != null) {
				removeMinLeaf(node.getLo(), node, pos, result);
			} else if (localX <= result.best) {
				result.node = node;
				result.nodeParent = parent;
				result.best = localX;
				result.pos = node.getDim();
				invariantBroken |= result.best == localX;
			}
		} else {
			//split in any other dimension.
			//First, check local key. 
			if (localX <= result.best) {
				result.node = node;
				result.nodeParent = parent;
				result.best = localX;
				result.pos = node.getDim();
				invariantBroken |= result.best == localX;
			}
			if (node.getLo() != null) {
				removeMinLeaf(node.getLo(), node, pos, result);
			}
			if (node.getHi() != null) {
				removeMinLeaf(node.getHi(), node, pos, result);
			}
		}
	}
	
	private void removeMaxLeaf(Node<T> node, Node<T> parent, int pos, RemoveResult<T> result) {
		//Split in 'interesting' dimension
		double localX = node.getKey()[pos];
		if (pos == node.getDim()) {
			//We strictly look for leaf nodes with left==null
			if (node.getHi() != null) {
				removeMaxLeaf(node.getHi(), node, pos, result);
			} else if (localX >= result.best) {
				result.node = node;
				result.nodeParent = parent;
				result.best = localX;
				result.pos = node.getDim();
				invariantBroken |= result.best == localX;
			}
		} else {
			//split in any other dimension.
			//First, check local key. 
			if (localX >= result.best) {
				result.node = node;
				result.nodeParent = parent;
				result.best = localX;
				result.pos = node.getDim();
				invariantBroken |= result.best == localX;
			}
			if (node.getLo() != null) {
				removeMaxLeaf(node.getLo(), node, pos, result);
			}
			if (node.getHi() != null) {
				 removeMaxLeaf(node.getHi(), node, pos, result);
			}
		}
	}
	
	/**
	 * Reinsert the key.
	 * @param oldKey old key
	 * @param newKey new key
	 * @return the value associated with the key or 'null' if the key was not found.
	 */
	@Override
	public T update(double[] oldKey, double[] newKey) {
		if (root == null) {
			return null;
		}
		T value = remove(oldKey);
		insert(newKey, value);
		return value;
	}
	
	/**
	 * Get the number of key-value pairs in the tree.
	 * @return the size
	 */
	@Override
	public int size() {
		return size;
	}

	/**
	 * Removes all elements from the tree.
	 */
	@Override
	public void clear() {
		size = 0;
		root = null;
		invariantBroken = false;
		modCount++;
	}

	/**
	 * Query the tree, returning all points in the axis-aligned rectangle between 'min' and 'max'.
	 * @param min lower left corner of query
	 * @param max upper right corner of query
	 * @return all entries in the rectangle
	 */
	@Override
	public KDIterator<T> query(double[] min, double[] max) {
		return new KDIterator<>(this, min, max);
	}

	static boolean isEnclosed(double[] point, double[] min, double[] max) {
		for (int i = 0; i < point.length; i++) {
			if (point[i] < min[i] || point[i] > max[i]) {
				return false;
			}
		}
		return true;
	}

	private static double distance(double[] p1, double[] p2) {
		double dist = 0;
		for (int i = 0; i < p1.length; i++) {
			double d = p1[i]-p2[i];
			dist += d * d;
		}
		return Math.sqrt(dist);
	}
	
	/**
	 * 1-nearest neighbor query.
	 * @param center
	 * @return Nearest neighbor
	 */
	public KDEntryDist<T> nnQuery(double[] center) {
		if (root == null) {
    		return null;
		}
    	KDEntryDist<T> candidate = new KDEntryDist<>(null, Double.POSITIVE_INFINITY);
   		rangeSearch1NN(root, center, candidate, Double.POSITIVE_INFINITY);
    	return candidate;
    }

    private double rangeSearch1NN(Node<T> node, double[] center, 
    		KDEntryDist<T> candidate, double maxRange) {
    	int pos = node.getDim();
    	if (node.getLo() != null && (center[pos] < node.getKey()[pos] || node.getHi() == null)) {
        	//go down
    		maxRange = rangeSearch1NN(node.getLo(), center, candidate, maxRange);
        	//refine result
    		if (center[pos] + maxRange >= node.getKey()[pos]) {
    			maxRange = addCandidate(node, center, candidate, maxRange);
        		if (node.getHi() != null) {
        			maxRange = rangeSearch1NN(node.getHi(), center, candidate, maxRange);
        		}
    		}
    	} else if (node.getHi() != null) {
        	//go down
    		maxRange = rangeSearch1NN(node.getHi(), center, candidate, maxRange);
        	//refine result
    		if (center[pos] <= node.getKey()[pos] + maxRange) {
    			maxRange = addCandidate(node, center, candidate, maxRange);
        		if (node.getLo() != null) {
        			maxRange = rangeSearch1NN(node.getLo(), center, candidate, maxRange);
        		}
    		}
    	} else {
    		//leaf -> first (probably best) match!
    		maxRange = addCandidate(node, center, candidate, maxRange);
    	}
    	return maxRange;
    }
        
    private double addCandidate(Node<T> node, double[] center, final KDEntryDist<T> candidate, double maxRange) {
    	double dist = distance(center, node.getKey());
    	if (dist >= maxRange) {
    		//don't add if too far away
    		//don't add if we already have an equally good result
    		return maxRange;
    	}
    	candidate.set(node, dist);
    	return dist;
    }
	
	public List<KDEntryDist<T>> knnQuery(double[] center, int k) {
		if (root == null) {
    		return Collections.emptyList();
		}
    	ArrayList<KDEntryDist<T>> candidates = new ArrayList<>(k);
   		rangeSearchKNN(root, center, candidates, k, Double.POSITIVE_INFINITY);
    	return candidates;
    }

    private double rangeSearchKNN(Node<T> node, double[] center, 
    		ArrayList<KDEntryDist<T>> candidates, int k, double maxRange) {
    	int pos = node.getDim();
    	if (node.getLo() != null && (center[pos] < node.getKey()[pos] || node.getHi() == null)) {
        	//go down
    		maxRange = rangeSearchKNN(node.getLo(), center, candidates, k, maxRange);
        	//refine result
    		if (center[pos] + maxRange >= node.getKey()[pos]) {
    			maxRange = addCandidate(node, center, candidates, k, maxRange);
        		if (node.getHi() != null) {
        			maxRange = rangeSearchKNN(node.getHi(), center, candidates, k, maxRange);
        		}
    		}
    	} else if (node.getHi() != null) {
        	//go down
    		maxRange = rangeSearchKNN(node.getHi(), center, candidates, k, maxRange);
        	//refine result
    		if (center[pos] <= node.getKey()[pos] + maxRange) {
    			maxRange = addCandidate(node, center, candidates, k, maxRange);
        		if (node.getLo() != null) {
        			maxRange = rangeSearchKNN(node.getLo(), center, candidates, k, maxRange);
        		}
    		}
    	} else {
    		//leaf -> first (probably best) match!
    		maxRange = addCandidate(node, center, candidates, k, maxRange);
    	}
    	return maxRange;
    }
    
	
    private static final Comparator<KDEntryDist<?>> compKnn =  
    		(KDEntryDist<?> point1, KDEntryDist<?> point2) -> {
    			double deltaDist = point1.dist() - point2.dist();
    			return deltaDist < 0 ? -1 : (deltaDist > 0 ? 1 : 0);
    		};

    
    private double addCandidate(Node<T> node, double[] center, 
    		ArrayList<KDEntryDist<T>> candidates, int k, double maxRange) {
    	//add ?
    	double dist = distance(center, node.getKey());
    	if (dist > maxRange) {
    		//don't add if too far away
    		return maxRange;
    	}
    	if (dist == maxRange && candidates.size() >= k) {
    		//don't add if we already have enough equally good results.
    		return maxRange;
    	}
    	KDEntryDist<T> cand;
    	if (candidates.size() >= k) {
    		cand = candidates.remove(k - 1);
    		cand.set(node, dist);
    	} else {
    		cand = new KDEntryDist<>(node, dist);
    	}
    	int insertionPos = Collections.binarySearch(candidates, cand, compKnn);
    	insertionPos = insertionPos >= 0 ? insertionPos : -(insertionPos+1);
    	candidates.add(insertionPos, cand);
    	return candidates.size() < k ? maxRange : candidates.get(candidates.size() - 1).dist();
    }
    
	
    private static class KDQueryIteratorKNN<T> implements QueryIteratorKNN<PointEntryDist<T>> {

    	private Iterator<? extends PointEntryDist<T>> it;
    	private final KDTree<T> tree;
    	
		public KDQueryIteratorKNN(KDTree<T> tree, double[] center, int k) {
			this.tree = tree;
			reset(center, k);
		}

		@Override
		public boolean hasNext() {
			return it.hasNext();
		}

		@Override
		public PointEntryDist<T> next() {
			return it.next();
		}

		@Override
		public void reset(double[] center, int k) {
			it = tree.knnQuery(center, k).iterator();
		}
    }
    
    /**
	 * Returns a printable list of the tree.
	 * @return the tree as String
	 */
    @Override
	public String toStringTree() {
		StringBuilder sb = new StringBuilder();
		if (root == null) {
			sb.append("empty tree");
		} else {
			toStringTree(sb, root, 0);
		}
		return sb.toString();
	}
	
	private void toStringTree(StringBuilder sb, Node<T> node, int depth) {
		String prefix = "";
		for (int i = 0; i < depth; i++) {
			prefix += ".";
		}
		//sb.append(prefix + " d=" + depth + NL);
		prefix += " ";
		if (node.getLo() != null) {
			toStringTree(sb, node.getLo(), depth+1);
		}
		sb.append(prefix + Arrays.toString(node.point()));
		sb.append(" v=" + node.value());
		sb.append(" l/r=");
		sb.append(node.getLo() == null ? null : Arrays.toString(node.getLo().point()));
		sb.append("/");
		sb.append(node.getHi() == null ? null : Arrays.toString(node.getHi().point()));
		sb.append(NL);
		if (node.getHi() != null) {
			toStringTree(sb, node.getHi(), depth+1);
		}
	}
	
	@Override
	public String toString() {
		return "KDTree;size=" + size + 
				";DEBUG=" + DEBUG + 
				";center=" + (root==null ? "null" : Arrays.toString(root.getKey()));
	}
	
	@Override
	public KDStats getStats() {
		KDStats s = new KDStats();
		if (root != null) {
			root.checkNode(s, 0);
		}
		return s;
	}
	
	/**
	 * Statistics container class.
	 */
	public static class KDStats {
		int nNodes;
		int maxDepth;
		public int getNodeCount() {
			return nNodes;
		}
		public int getMaxDepth() {
			return maxDepth;
		}
	}

	@Override
	public int getDims() {
		return dims;
	}

	@Override
	public QueryIterator<PointEntry<T>> iterator() {
		if (root == null) {
			return query(new double[dims], new double[dims]);
		}
		//return query(root.);
		//TODO
		throw new UnsupportedOperationException();
	}

	@Override
	public KDEntryDist<T> query1NN(double[] center) {
		return nnQuery(center);
	}

	@Override
	public KDQueryIteratorKNN<T> queryKNN(double[] center, int k) {
		return new KDQueryIteratorKNN<>(this, center, k);
	}

	@Override
	public int getNodeCount() {
		return getStats().getNodeCount();
	}

	@Override
	public int getDepth() {
		return getStats().getMaxDepth();
	}
	
	Node<T> getRoot() {
		return root;
	}
}
