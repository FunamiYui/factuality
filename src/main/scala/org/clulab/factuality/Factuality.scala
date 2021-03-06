package org.clulab.factuality

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.{FileWriter, PrintWriter}
import java.net.JarURLConnection
import java.net.URI

import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.{ArrayBuffer, ListBuffer}
import edu.cmu.dynet._
import edu.cmu.dynet.Expression._
import Factuality._
import org.clulab.factuality.utils.MathUtils
import org.clulab.factuality.utils.Serializer
import org.clulab.fatdynet.utils.CloseableModelSaver
import org.clulab.fatdynet.utils.Closer.AutoCloser

import scala.collection.mutable
import scala.io.Source
import scala.util.Random
import scala.util.Try

class Factuality {
  var model:LstmParameters = _

  /**
    * Trains on the given training sentences, and report mae and r after each epoch on development sentences
    * @param trainSentences Training sentences
    * @param devSentences Development/validation sentences, used for logging purposes only
    */
  def train(trainSentences: Array[Array[String]], devSentences:Array[Array[String]], devPrefix: String): Unit = {
    //val trainer = new SimpleSGDTrainer(model.parameters, learningRate = 0.01f)
    val trainer = new RMSPropTrainer(model.parameters)
    var cummulativeLoss = 0.0
    var numTagged = 0
    var sentCount = 0
    var sentences = trainSentences
    val rand = new Random(RANDOM_SEED)

    for(epoch <- 0 until EPOCHS) {
      sentences = MathUtils.randomize(sentences, rand)

      logger.info(s"Started epoch $epoch.")
      for(sentence <- sentences) {
        sentCount += 1
        ComputationGraph.renew()

        // predict tag emission scores for one sentence, from the biLSTM hidden states
        val words = sentence.slice(2,sentence.length)
        val p = sentence(1).toInt   //p is the position of predicate in the sentence, start from 0
        val emissionScore = emissionScoresAsExpressions(words, p)  

        val goldScore = sentence(0).toFloat   //factuality

        // compute loss for this sentence
        val loss = Expression.huberDistance(emissionScore, Expression.input(goldScore), 1.0f)

        cummulativeLoss += loss.value().toFloat

        if(sentCount % 1000 == 0) {
          logger.info("Cummulative loss: " + cummulativeLoss)
          cummulativeLoss = 0.0
        }

        // backprop
        ComputationGraph.backward(loss)
        trainer.update()
      }

      // check dev performance in this epoch
      if(devSentences.nonEmpty)
        evaluate(devSentences, devPrefix, epoch)
    }
  }

  
  def printCoNLLOutput(pw:PrintWriter, sent:Array[String], pred:Float): Unit = {
    pw.println(pred.toString + " " +  sent.mkString(" ") + "\n")
  }
 
  def mae_r(golds:Array[Float], preds:Array[Float]): (Double, Double) = {
    assert(golds.length == preds.length)

    var mae = 0.0
    var true_sum = 0.0
    var pred_sum = 0.0
    val n = golds.length

    for(i <- golds.indices) {
        mae = mae + math.abs(preds(i) - golds(i))
        true_sum = true_sum + golds(i)
        pred_sum = pred_sum + preds(i)
    }

    mae = mae / n
    val true_mean = true_sum / n
    val pred_mean = pred_sum / n


    var sum_prod = 0.0
    var true_square_sum = 0.0
    var pred_square_sum = 0.0
    for(i <- golds.indices) {
      sum_prod = sum_prod + (golds(i) - true_mean) * (preds(i) - pred_mean)
      true_square_sum = true_square_sum + (golds(i) - true_mean) * (golds(i) - true_mean) 
      pred_square_sum = pred_square_sum + (preds(i) - pred_mean) * (preds(i) - pred_mean)
    }

    val r = sum_prod / math.sqrt(true_square_sum) / math.sqrt(pred_square_sum)

    (mae, r)
  }

  def evaluate(sentences:Array[Array[String]], prefix:String, epoch:Int): Unit = {
    evaluate(sentences, prefix, "development", epoch)
  }

  def evaluate(sentences:Array[Array[String]], prefix:String): Unit = {
    evaluate(sentences, prefix, "testing", -1)
  }

  /** Logs mae and r on devSentences; also saves the output in the file dev.output.<EPOCH> */
  def evaluate(sentences:Array[Array[String]], prefix:String, name:String, epoch:Int): Unit = {

    val pw = new PrintWriter(new FileWriter(prefix + epoch))
    logger.debug(s"Started evaluation on the $name dataset...")

    val preds = new ArrayBuffer[Float]()
    for(sent <- sentences) {

      val p = sent(1).toInt   //p is the position of predicate in the sentence, start from 0
      val words = sent.slice(2,sent.length)

      // the input of predict function is in the format of (sentence words, predicate position)
      val pred = predict(words, p)
      preds += pred

      printCoNLLOutput(pw, sent, pred)
    }

    val golds = sentences.map(_(0).toFloat)
    val (mae, r) = mae_r(golds, preds.toArray)

    pw.close()
    logger.info(s"Mean absolute error (MAE) on $name sentences: " + mae)
    logger.info(s"Pearson correlation coefficient (r) on $name sentences: " + r)
  }

  /**
    * Generates tag emission scores for the words in this sequence, stored as Expressions
    * @param words One training or testing sentence
      @param p predicates' position in the training or testing sentence
    */
  def emissionScoresAsExpressions(words: Array[String], p:Int): Expression = {
    val embeddings = words.map(mkEmbedding)

    val fwStates = transduce(embeddings, model.fwRnnBuilder)
    val bwStates = transduce(embeddings.reverse, model.bwRnnBuilder).toArray.reverse
    assert(fwStates.size == bwStates.length)
    val states = concatenateStates(fwStates, bwStates).toArray
    assert(states.length == words.length)

 
    val v1 = parameter(model.v1)
    val b1 = parameter(model.b1)
    val v2 = parameter(model.v2)
    val b2 = parameter(model.b2)

    val regression = Expression.dotProduct(v2, Expression.rectify(v1 * states(p) + b1)) + b2


      // if(doDropout) {
      //   l1 = Expression.dropout(l1, DROPOUT_PROB)
      // }

      // emissionScores.add(O * l1)
    // }

    regression
  }

  /**
    * This is the API main entry point: it predicts the factuality score of the predicate at position p in the sentence
    * @param words The words in the sentence
    * @param p The position of the predicate
    * @return The factuality score
    */
  def predict(words: Array[String], p:Int):Float = {
    // Note: this block MUST be synchronized. Currently the computational graph in DyNet is a static variable.
    val emissionScore: Expression = synchronized {
      ComputationGraph.renew()
      emissionScoresAsExpressions(words, p) // these scores do not have softmax
    }
    emissionScore.value().toFloat()
  }

  def concatenateStates(l1: Iterable[Expression], l2: Iterable[Expression]): Iterable[Expression] = {
    val c = new ArrayBuffer[Expression]()
    for(e <- l1.zip(l2)) {
      c += concatenate(e._1, e._2)
    }
    c
  }

  def mkEmbedding(word: String):Expression = {
    //
    // make sure you preprocess the word similarly to the embedding library used!
    //   GloVe large does not do any preprocessing
    //   GloVe small lowers the case
    //   Our Word2Vec uses Word2Vec.sanitizeWord
    //
    val sanitized = word.toLowerCase() // Word2Vec.sanitizeWord(word)

    val wordEmbedding =
      if(model.w2i.contains(sanitized))
      // found the word in the known vocabulary
        lookup(model.lookupParameters, model.w2i(sanitized))
      else {
        // not found; return the embedding at position 0, which is reserved for unknown words
        lookup(model.lookupParameters, 0)
      }

    // biLSTM over character embeddings
    val charEmbedding =
      mkCharacterEmbedding(word)

    concatenate(wordEmbedding, charEmbedding)
  }

  def mkCharacterEmbedding(word: String): Expression = {
    //println(s"make embedding for word [$word]")
    val charEmbeddings = new ArrayBuffer[Expression]()
    for(i <- word.indices) {
      if(model.c2i.contains(word.charAt(i)))
        charEmbeddings += lookup(model.charLookupParameters, model.c2i(word.charAt(i)))
    }
    val fwOut = transduce(charEmbeddings, model.charFwRnnBuilder).last
    val bwOut = transduce(charEmbeddings.reverse, model.charBwRnnBuilder).last
    concatenate(fwOut, bwOut)
  }

  def transduce(embeddings:Iterable[Expression], builder:RnnBuilder): Iterable[Expression] = {
    builder.newGraph()
    builder.startNewSequence()
    val states = embeddings.map(builder.addInput)
    states
  }

  def initialize(trainSentences:Array[Array[String]], w2v: Embedder): Unit = {
    val (w2i, c2i) = mkVocabs(trainSentences, w2v)
    // logger.debug(s"Tag vocabulary has ${t2i.size} entries.")
    logger.debug(s"Word vocabulary has ${w2i.size} entries (including 1 for unknown).")
    logger.debug(s"Character vocabulary has ${c2i.size} entries.")

    logger.debug("Initializing DyNet...")
    Initialize.initialize(Map("random-seed" -> RANDOM_SEED, "dynet-mem" -> "1024"))
    model = mkParams(w2i, c2i, w2v.dimensions)

    model.initializeEmbeddings(w2v)
    // model.initializeTransitions()

    logger.debug("Completed initialization.")
  }
}

class LstmParameters(
  var w2i:Map[String, Int],
  val c2i:Map[Char, Int],
  val parameters:ParameterCollection,
  var lookupParameters:LookupParameter,
  val fwRnnBuilder:RnnBuilder,
  val bwRnnBuilder:RnnBuilder,
  val v1:Parameter,
  val b1:Parameter,
  val v2:Parameter,
  val b2:Parameter,
  val charLookupParameters:LookupParameter,
  val charFwRnnBuilder:RnnBuilder,
  val charBwRnnBuilder:RnnBuilder) {

  protected def toFloatArray(doubles: Array[Double]): Array[Float] = {
    val floats = new Array[Float](doubles.length)
    for (i <- doubles.indices) {
      floats(i) = doubles(i).toFloat
    }
    floats
  }

  protected def add(dst:Array[Double], src:Array[Double]): Unit = {
    assert(dst.length == src.length)
    for(i <- dst.indices) {
      dst(i) += src(i)
    }
  }


  def initializeEmbeddings(w2v:Embedder): Unit = {
    logger.debug("Initializing DyNet embedding parameters...")
    for(word <- w2v.words){
      lookupParameters.initialize(w2i(word), new FloatVector(toFloatArray(w2v(word))))
    }
    logger.debug(s"Completed initializing embedding parameters for a vocabulary of size ${w2v.size}.")
  }
}

object Factuality {
  val logger:Logger = LoggerFactory.getLogger(classOf[Factuality])

  val EPOCHS = 1
  val RANDOM_SEED = 2522620396L // used for both DyNet, and the JVM seed for shuffling data
  val DROPOUT_PROB = 0.1f
  val DO_DROPOUT = true

  val RNN_STATE_SIZE = 300
  val NONLINEAR_SIZE = 300
  val RNN_LAYERS = 2
  val CHAR_RNN_LAYERS = 1
  val CHAR_EMBEDDING_SIZE = 32
  val CHAR_RNN_STATE_SIZE = 16

  val UNK_WORD = "<UNK>"

  val LOG_MIN_VALUE:Float = -10000

  val USE_DOMAIN_CONSTRAINTS = true

  def apply(modelFilename:String, fromResource: Boolean = true): Factuality = {
    // make sure DyNet is initialized!
    Initialize.initialize(Map("random-seed" -> RANDOM_SEED, "dynet-mem" -> "1024"))

    val rnn = new Factuality()

    rnn.model = Try(loadFromFile(modelFilename))
        .getOrElse(loadFromResource(modelFilename))
    rnn
  }

  protected def save[T](printWriter: PrintWriter, values: Map[T, Int], comment: String): Unit = {
    printWriter.println("# " + comment)
    values.foreach { case (key, value) =>
      printWriter.println(s"$key\t$value")
    }
    printWriter.println() // Separator
  }

  protected def save[T](printWriter: PrintWriter, values: Array[T], comment: String): Unit = {
    printWriter.println("# " + comment)
    values.foreach(printWriter.println)
    printWriter.println() // Separator
  }

  protected def save[T](printWriter: PrintWriter, value: Long, comment: String): Unit = {
    printWriter.println("# " + comment)
    printWriter.println(value)
    printWriter.println() // Separator
  }

  def save(modelFilename: String, rnnParameters: LstmParameters):Unit = {
    val dynetFilename = modelFilename + ".rnn"
    val x2iFilename = modelFilename + ".x2i"

    new CloseableModelSaver(dynetFilename).autoClose { modelSaver =>
      modelSaver.addModel(rnnParameters.parameters, "/all")
    }

    Serializer.using(new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(new FileOutputStream(x2iFilename)), "UTF-8"))) { printWriter =>
      save(printWriter, rnnParameters.w2i, "w2i")
      save(printWriter, rnnParameters.c2i, "c2i")
      val dim = rnnParameters.lookupParameters.dim().get(0)
      save(printWriter, dim, "dim")
    }
  }

  protected def loadX2I(source: Source): (Map[String, Int], Map[Char, Int], Int) = {
    def stringToString(string: String): String = string
    def stringToChar(string: String): Char = string.charAt(0)

    val byLineStringMapBuilder = new ByLineMapBuilder(stringToString)
    val byLineCharMapBuilder = new ByLineMapBuilder(stringToChar)

    val lines = source.getLines()
    val w2i = byLineStringMapBuilder.build(lines)
    // val t2i = byLineStringMapBuilder.build(lines)
    val c2i = byLineCharMapBuilder.build(lines)
    // val i2t = new ByLineArrayBuilder().build(lines)
    val dim = new ByLineIntBuilder().build(lines)

    (w2i, c2i, dim)
  }

  protected def loadFromResource(modelFilename: String): LstmParameters =
      loadFromResource(modelFilename, this.getClass.getClassLoader)

  protected def loadFromResource(modelFilename: String, classLoader: ClassLoader): LstmParameters = {
    val dynetFilename = modelFilename + ".rnn"
    val x2iFilename = modelFilename + ".x2i"
    // This code will soon appear in fatdynet
    val zipname = {
      val resourceName = dynetFilename
      val url = classLoader.getResource(resourceName)
      if (Option(url).isEmpty)
        throw new RuntimeException(s"ERROR: cannot locate the model file $resourceName!")
      val protocol = url.getProtocol
      if (protocol == "jar") {
        // The resource has been jarred, and must be extracted with a ZipModelLoader.
        val jarUrl = url.openConnection().asInstanceOf[JarURLConnection].getJarFileURL
        val protocol2 = jarUrl.getProtocol
        assert(protocol2 == "file")
        val uri = new URI(jarUrl.toString)
        // This converts both percent encoded characters and file separators.
        val nativeJarFileName = new File(uri).getCanonicalPath

        nativeJarFileName
      }
      else
        throw new RuntimeException(s"ERROR: cannot process the model file $resourceName!")
    }

    val (w2i, c2i, dim) = Serializer.using(Source.fromResource(x2iFilename, classLoader)("UTF-8")) { source =>
      loadX2I(source)
    }

    val oldModel = {
      val model = mkParams(w2i, c2i, dim)

      new ZipModelLoader(dynetFilename, zipname).populateModel(model.parameters, "/all")
      model
    }

    oldModel
  }

  protected def loadFromFile(modelFilename:String):LstmParameters = {
    val dynetFilename = modelFilename + ".rnn"
    val x2iFilename = modelFilename + ".x2i"
    val (w2i, c2i, dim) = Serializer.using(Source.fromFile(x2iFilename, "UTF-8")) { source =>
      loadX2I(source)
    }

    val oldModel = {
      val model = mkParams(w2i, c2i, dim)
      new ModelLoader(dynetFilename).populateModel(model.parameters, "/all")
      model
    }

    oldModel
  }

  def fromIndexToString(s2i: Map[String, Int]):Array[String] = {
    var max = Int.MinValue
    for(v <- s2i.values) {
      if(v > max) {
        max = v
      }
    }
    assert(max > 0)
    val i2s = new Array[String](max + 1)
    for(k <- s2i.keySet) {
      i2s(s2i(k)) = k
    }
    i2s
  }

  def mkVocabs(trainSentences:Array[Array[String]], w2v:Embedder): (Map[String, Int], Map[Char, Int]) = {
    val chars = new mutable.HashSet[Char]()
    for(sentence <- trainSentences) {
      for(word <- sentence.slice(2,sentence.length)) {
        for(i <- word.indices) {
          chars += word.charAt(i)
        }
      }
    }

    val commonWords = new ListBuffer[String]
    commonWords += UNK_WORD // the word at position 0 is reserved for unknown words
    for(w <- w2v.words.sorted) {
      commonWords += w
    }

    val w2i = commonWords.zipWithIndex.toMap
    val c2i = chars.toList.sorted.zipWithIndex.toMap

    (w2i, c2i)
  }

  def mkParams(w2i:Map[String, Int], c2i:Map[Char, Int], embeddingDim:Int): LstmParameters = {
    val parameters = new ParameterCollection()
    val lookupParameters = parameters.addLookupParameters(w2i.size, Dim(embeddingDim))
    val embeddingSize = embeddingDim + 2 * CHAR_RNN_STATE_SIZE
    val fwBuilder = new LstmBuilder(RNN_LAYERS, embeddingSize, RNN_STATE_SIZE, parameters)
    val bwBuilder = new LstmBuilder(RNN_LAYERS, embeddingSize, RNN_STATE_SIZE, parameters)
    val v1 = parameters.addParameters(Dim(NONLINEAR_SIZE, 2 * RNN_STATE_SIZE))
    val b1 = parameters.addParameters(Dim(NONLINEAR_SIZE))
    val v2 = parameters.addParameters(Dim(NONLINEAR_SIZE))
    val b2 = parameters.addParameters(Dim(1))

    logger.debug("Created parameters.")

    val charLookupParameters = parameters.addLookupParameters(c2i.size, Dim(CHAR_EMBEDDING_SIZE))
    val charFwBuilder = new LstmBuilder(CHAR_RNN_LAYERS, CHAR_EMBEDDING_SIZE, CHAR_RNN_STATE_SIZE, parameters)
    val charBwBuilder = new LstmBuilder(CHAR_RNN_LAYERS, CHAR_EMBEDDING_SIZE, CHAR_RNN_STATE_SIZE, parameters)

    new LstmParameters(w2i, c2i,
      parameters, lookupParameters, fwBuilder, bwBuilder, v1, b1, v2, b2,
      charLookupParameters, charFwBuilder, charBwBuilder)
  }

  def sentences2Instances(rawSentences: Array[Array[Row]]): Array[Array[String]] = {
    // convert to the format that each line corresponds to one predicate,
    // specifically, each line is in the format of [factuality socre, predicate position, words in sentence] 

    val sentences = new ArrayBuffer[Array[String]]()

    for(rawsentence <- rawSentences) {
      val word = rawsentence.map(_.get(1))
      val fact = rawsentence.map(_.get(2))

      for(i <- fact.indices) {
        if(!(fact(i) == "_")){
          var sentence = new ArrayBuffer[String]()
          sentence += fact(i).toString
          sentence += i.toString
          sentence ++= word
          sentences += sentence.toArray
        }
      }

    }
    sentences.toArray
  }
}
