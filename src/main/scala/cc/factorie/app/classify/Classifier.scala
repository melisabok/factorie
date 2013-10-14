/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.app.classify
import cc.factorie.variable._
import cc.factorie.infer._
import scala.collection.mutable.ArrayBuffer
import cc.factorie.variable.{LabeledDiscreteEvaluation, LabeledMutableDiscreteVar, CategoricalDomain}
import cc.factorie.la.Tensor1
import cc.factorie.optimize._
import cc.factorie.util.DoubleSeq
import cc.factorie.la.SingletonBinaryTensor1

/** A record of the result of applying a Classifier to a variable. */
class Classification[V<:DiscreteVar](val _1:V, score:Tensor1) extends MulticlassValueClassification(score) with DiscreteMarginal1[V] {
  def bestValue = _1.domain.apply(bestLabelIndex)
  def bestValueString: String = bestValue match {
    case cv:CategoricalValue[_] => cv.category.toString
    case dv:DiscreteValue => dv.intValue.toString
  }
}

// Classifiers

/** Performs iid prediction of a DiscreteVar. */
trait Classifier[L<:DiscreteVar] {
  type ClassificationType <: Classification[L] // TODO Use this.
  // Get classification record without changing the value of the label
  def classification(v:L): Classification[L]
  def classifications(labels: Iterable[L]): Seq[Classification[L]] = labels.toSeq.par.map(label => classification(label)).seq
  // Get classification record and also set the label to its best scoring value 
  def classify[L2<:L with MutableDiscreteVar](v:L2): Classification[L] = { val c = classification(v); v := c.bestLabelIndex; c }
  def classify(labels: Iterable[L with MutableDiscreteVar]): Seq[Classification[L]] = labels.toSeq.par.map(classify(_)).seq
  def bestIndex(v:L): Int = classification(v).bestLabelIndex
  // TODO It might be nice to have a weighted version of this.  We could do this with a LabelList. :-) -akm
  def accuracy(labels:Iterable[L with LabeledDiscreteVar]): Double = {
    var correct = 0.0; var total = 0.0
    labels.foreach(label => { total += 1.0; if (bestIndex(label) == label.targetIntValue) correct += 1.0 })
    correct / total
  } 
}

/** A Classifier in which the "input, observed" object to be classified is a VectorVar (with value Tensor1). */
trait VectorClassifier[V<:DiscreteVar, Features<:VectorVar] extends Classifier[V] with MulticlassValueClassifier[Tensor1] {
  def labelToFeatures: V=>Features
}

/** A VectorClassifier in which the score for each class is a dot-product between the observed feature vector and a vector of parameters.
    Examples include NaiveBayes, MultivariateLogisticRegression, LinearSVM, and many others.
    Counter-examples include KNearestNeighbor. */
class LinearVectorClassifier[L<:DiscreteVar,F<:VectorVar](numLabels:Int, numFeatures:Int, val labelToFeatures:L=>F) extends LinearMulticlassValueClassifier(numLabels, numFeatures) with VectorClassifier[L,F] {
  def classification(v:L): Classification[L] = new Classification(v, score(labelToFeatures(v).value))
  override def bestIndex(v:L): Int = score(labelToFeatures(v).value).maxIndex
}


// Classifier trainers

/** An object that can create and train a VectorClassifier given labeled training data. */
trait VectorClassifierTrainer {
  def train[L<:LabeledDiscreteVar,F<:VectorVar](labels:Iterable[L], l2f:L=>F): VectorClassifier[L,F]
}

//trait ExistingVectorClassifierTrainer[C<:VectorClassifier[L,F]] { def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, labels:Iterable[L], l2f:L=>F): C }

/** An object that can create and train a LinearVectorClassifier (or train a pre-existing LinearVectorClassifier) given labeled training data. */
trait LinearVectorClassifierTrainer extends VectorClassifierTrainer {
  /** Create a new LinearVectorClassifier, not yet trained. */
  protected def newClassifier[L<:LabeledDiscreteVar,F<:VectorVar](labelDomainSize:Int, featureDomainSize:Int, l2f:L=>F): LinearVectorClassifier[L,F] = new LinearVectorClassifier(labelDomainSize, featureDomainSize, l2f)
  /** Create, train and return a new LinearVectorClassifier */
  def train[L<:LabeledDiscreteVar,F<:VectorVar](labels:Iterable[L], l2f:L=>F): LinearVectorClassifier[L,F] = train(newClassifier(labels.head.domain.size, l2f(labels.head).domain.dimensionSize, l2f), labels, l2f)
  /** Train (and return) an already-created (perhaps already partially-trained) LinearVectorClassifier. */
  def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, trainLabels:Iterable[L], l2f:L=>F): C
}

///** An object that can train a pre-existing LinearVectorClassifier given labeled training data. */
//trait ExistingLinearVectorClassifierTrainer { def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, labels:Iterable[L], l2f:L=>F): C }

/** A LinearVectorClassifierTrainer that uses the cc.factorie.optimize package to estimate parameters. */
class OptimizingLinearVectorClassifierTrainer(
  val optimizer: GradientOptimizer,
  val useParallelTrainer: Boolean,
  val useOnlineTrainer: Boolean,
  val objective: LinearObjectives.Multiclass,
  val maxIterations: Int,
  val miniBatch: Int,
  val nThreads: Int)(implicit random: scala.util.Random) extends LinearVectorClassifierTrainer
{
  // TODO This is missing weights on Examples.  I think passing a Seq[Double] is error prone, and am tempted to go back to LabelList. -akm
  /** Create a sequence of Example instances for obtaining the gradients used for training. */
  def examples[L<:LabeledDiscreteVar,F<:VectorVar](classifier:LinearVectorClassifier[L,F], labels:Iterable[L], l2f:L=>F, objective:LinearObjectives.Multiclass): Seq[LinearMulticlassExample] =
    labels.toSeq.map(l => new LinearMulticlassExample(classifier.weights, l2f(l).value, l.targetIntValue, objective))
    
  /** Train the classifier to convergence, calling the diagnostic function once after each iteration.
      This is the base method called by the other simpler train methods. */
  def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, trainLabels:Iterable[L], l2f:L=>F, diagnostic:C=>Unit): C = {
    Trainer.train(parameters=classifier.parameters, examples=examples(classifier, trainLabels, l2f, objective), maxIterations=maxIterations, evaluate = ()=>diagnostic(classifier), optimizer=optimizer, useParallelTrainer=useParallelTrainer, useOnlineTrainer=useOnlineTrainer, miniBatch=miniBatch, nThreads=nThreads)
    classifier
  }
  /** Return a function suitable for passing in as the diagnostic to train which prints the accuracy on the testLabels */
  def defaultTestDiagnostic[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:LinearVectorClassifier[L,F], trainLabels:Iterable[L], testLabels:Iterable[L]): C=>Unit = 
    (c:C) => println(f"Test accuracy: ${classifier.accuracy(testLabels)}%1.4f")
  /** Return a function suitable for passing in as the diagnostic to train which prints the accuracy on the trainLabels and the testLabels */
  def defaultTrainAndTestDiagnostic[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:LinearVectorClassifier[L,F], trainLabels:Iterable[L], testLabels:Iterable[L]): C=>Unit = 
    (c:LinearVectorClassifier[L,F]) => println(f"Train accuracy: ${classifier.accuracy(trainLabels)}%1.4f\nTest  accuracy: ${classifier.accuracy(testLabels)}%1.4f")
  /** Train the classifier to convergence, calling a test-accuracy-printing diagnostic function once after each iteration. */
  def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, trainLabels:Iterable[L], testLabels:Iterable[L], l2f:L=>F): C =
    train(classifier, trainLabels, l2f, defaultTestDiagnostic(classifier, trainLabels, testLabels))
  /** Train the classifier to convergence, calling no diagnostic function. */
  def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, trainLabels:Iterable[L], l2f:L=>F): C = {
    train(classifier, trainLabels, l2f, (c:LinearVectorClassifier[L,F]) => ())
    classifier
  }
}

/** An OptimizingLinearVectorClassifierTrainer pre-tuned with default arguments well-suited to online training, operating on the gradient of one Example at a time. */
class OnlineOptimizingLinearVectorClassifierTrainer(
  useParallel:Boolean = false,
  optimizer: GradientOptimizer = new AdaGrad with ParameterAveraging,
  objective: LinearObjectives.Multiclass = LinearObjectives.sparseLogMulticlass,
  maxIterations: Int = 3,
  miniBatch: Int = -1,
  nThreads: Int = Runtime.getRuntime.availableProcessors())(implicit random: scala.util.Random)
  extends OptimizingLinearVectorClassifierTrainer(optimizer, useParallel, useOnlineTrainer = true, objective, maxIterations, miniBatch, nThreads)

/** An OptimizingLinearVectorClassifierTrainer pre-tuned with default arguments well-suited to batch training, operating on all the gradients of the Examples together. */
class BatchOptimizingLinearVectorClassifierTrainer(useParallel:Boolean = true,
  optimizer: GradientOptimizer = new LBFGS with L2Regularization,
  objective: LinearObjectives.Multiclass = LinearObjectives.sparseLogMulticlass,
  maxIterations: Int = 200,
  nThreads: Int = Runtime.getRuntime.availableProcessors())(implicit random: scala.util.Random)
  extends OptimizingLinearVectorClassifierTrainer(optimizer, useParallel, useOnlineTrainer = false, objective, maxIterations, -1, nThreads)

/** An OptimizingLinearVectorClassifierTrainer pre-tuned with default arguments well-suited to training an L2-regularized linear SVM. */
class SVMLinearVectorClassifierTrainer(parallel: Boolean=false)(implicit random: scala.util.Random) extends OptimizingLinearVectorClassifierTrainer(optimizer=null, useParallelTrainer=parallel, useOnlineTrainer=false, objective=null, miniBatch= -1, maxIterations= -1, nThreads= -1) {
  override def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, trainLabels:Iterable[L], l2f:L=>F, diagnostic:C=>Unit): C = {
    val ll = trainLabels.map(_.targetIntValue).toArray
    val ff = trainLabels.map(label => l2f(label).value).toArray[Tensor1]
    val numLabels = classifier.weights.value.dim1
    val weightTensor = {
      if (parallel) (0 until numLabels).par.map { label => (new LinearL2SVM).train(ff, ll, label) } // TODO We should allow setting of meta-parameters here. -akm
      else (0 until numLabels).map { label => (new LinearL2SVM).train(ff, ll, label) }
    }
    val weightsValue = classifier.weights.value
    for (f <- 0 until weightsValue.dim2; (l,t) <- (0 until numLabels).zip(weightTensor)) {
      weightsValue(f,l) = t(f)
    }
    diagnostic(classifier)
    classifier
  }
}

/** Creates a trained naive Bayes classifier by counting feature occurrences, smoothed with pseudo-counts (m-Estimates).
    Note that contrary to tradition, this naive Bayes classifier does not include a "bias" weight P(class); it only includes the feature weights, P(feature|class).
    If you want a "bias" weight you must include in your data a feature that always has value 1.0. */
class NaiveBayesClassifierTrainer(pseudoCount:Double = 0.1) extends LinearVectorClassifierTrainer {
  def train[C<:LinearVectorClassifier[L,F],L<:LabeledDiscreteVar,F<:VectorVar](classifier:C, trainLabels:Iterable[L], l2f:L=>F): C = {
    val labelSize = trainLabels.head.domain.size
    val featureSize = l2f(trainLabels.head).domain.dimensionSize
     // Collecting the bias weights afterall, in case we change our minds later, and store them in the classifier -akm
    //val bias = new DenseProportions1(labelSize)
    val evidence = Seq.tabulate(labelSize)(i => new DenseProportions1(featureSize))
    // Note: this doesn't actually build the graphical model, it just gathers smoothed counts, never creating factors
    // Incorporate smoothing, with simple +m smoothing
    //bias.masses += labelPseudoCount
    for (li <- 0 until labelSize) evidence(li).masses += pseudoCount
    // Incorporate evidence
    for (label <- trainLabels) {
      val targetIndex = label.targetIntValue
      //bias.masses += (targetIndex, 1.0)
      val features = l2f(label)
      features.value.foreachActiveElement((featureIndex, featureValue) => {
        evidence(targetIndex).masses += (featureIndex, featureValue)
      })
    }
    // Put results into the classifier parameters
    val tensor2: cc.factorie.la.Tensor2 = classifier.weights.value
    for (li <- 0 until labelSize) {
      val p = evidence(li)
      for (fi <- 0 until featureSize)
        tensor2(fi, li) = math.log(p(fi))
    }
    classifier
  }
}


// Decision trees.  Just one simple example so far. -akm

class DecisionTreeClassifier[L<:DiscreteVar,F<:VectorVar](val tree:DTree, val labelToFeatures:L=>F) extends VectorClassifier[L,F] {
  def classification(label:L): Classification[L] = new Classification(label, score(labelToFeatures(label).value))
  def score(features: Tensor1) = DTree.score(features, tree)
}

class ID3DecisionTreeClassifier(implicit random: scala.util.Random) extends VectorClassifierTrainer {
  def train[L<:LabeledDiscreteVar,F<:VectorVar](labels:Iterable[L], l2f:L=>F): DecisionTreeClassifier[L,F] = {
    val labelSize = labels.head.domain.size
    val instances = labels.toSeq.map(label => DecisionTreeTrainer.Instance(l2f(label).value, new SingletonBinaryTensor1(labelSize, label.targetIntValue), 1.0))
    val treeTrainer = new ID3DecisionTreeTrainer // TODO We could make this a flexible choice later. -akm
    val dtree = treeTrainer.train(instances)
    new DecisionTreeClassifier(dtree, l2f)
  }
}

