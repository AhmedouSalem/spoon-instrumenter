# 🛠️ Spoon Instrumenter – Injection automatique de logs

Ce projet fournit un **outil d’instrumentation automatique du code source Java** basé sur le framework **Spoon**.  
Il permet d’injecter des **instructions de logging structurées** dans une application backend Spring Boot **sans modifier manuellement la logique métier**.

Il est utilisé dans le cadre du **TP Logging & Observability** afin de générer des logs exploitables pour l’analyse du comportement des utilisateurs.

---

## 🎯 Objectif du projet

L’objectif principal de cet instrumenteur est de :

- identifier automatiquement les points pertinents d’instrumentation,
- injecter des logs de manière cohérente et systématique,
- préserver l’architecture et le comportement initial de l’application,
- produire une application **instrumentée et exécutable**.

L’instrumentation cible exclusivement la **couche Service**, qui représente le niveau métier le plus pertinent pour observer les opérations applicatives (lectures, écritures, requêtes coûteuses).

---

## 🧱 Structure du projet

Ce projet Maven contient **deux classes principales** :

```
spoon-instrumenter
├── InstrumenterMain.java
└── LoggingInjectorProcessor.java
```

---

## 1️⃣ InstrumenterMain

`InstrumenterMain` est le **point d’entrée** de l’outil d’instrumentation.

Ses responsabilités sont les suivantes :
- valider la structure du projet d’entrée (présence de `pom.xml`, `src/main/java`),
- copier intégralement le projet original dans un nouveau dossier cible,
- exécuter Spoon dans un répertoire temporaire,
- injecter uniquement les fichiers `*ServiceImpl.java` instrumentés,
- ajouter automatiquement les dépendances nécessaires au logging JSON,
- générer un fichier `logback-spring.xml` si absent.

👉 Le projet original n’est **jamais modifié directement**.

---

## 2️⃣ LoggingInjectorProcessor

`LoggingInjectorProcessor` est un **processeur Spoon personnalisé** chargé de l’injection des logs.

Il réalise les opérations suivantes :
- sélection des classes se terminant par `ServiceImpl`,
- ajout automatique d’un logger SLF4J si absent,
- instrumentation de chaque méthode publique,
- insertion d’une instruction de log au début du corps de la méthode.

Le type d’événement est automatiquement déduit à partir du nom de la méthode.

---

## 🔍 Stratégie d’instrumentation

### 🎯 Couche ciblée
- Uniquement la **couche Service** (`*ServiceImpl`)
- Les contrôleurs et repositories ne sont pas instrumentés volontairement.

### 🧾 Structure des logs injectés

Chaque log généré contient les champs suivants :
- `event` : `DB_READ`, `DB_WRITE` ou `MOST_EXPENSIVE_SEARCH`
- `action` : `READ`, `WRITE` ou `MOST_EXPENSIVE`
- `class` : nom de la classe service
- `method` : nom de la méthode
- identifiants métier optionnels (`productId`, `categoryId`, etc.)

Les logs sont produits avec **SLF4J** et exportés au format **JSON**.

---

## 📦 Format de sortie des logs

- Fichier généré :
  ```
  logs/app.jsonl
  ```
- Format : **JSON Lines**
- Framework de logging : **Logback**
- Encodeur : **logstash-logback-encoder**

Les logs générés correspondent uniquement aux événements applicatifs, sans logs techniques du framework.

---

## ▶️ Exécution de l’instrumenteur

### Prérequis
- Java 17+
- Maven
- Une application Spring Boot utilisant Maven

### Commande d’exécution

```bash
java -jar spoon-instrumenter.jar <chemin_projet_original> <chemin_projet_instrumenté>
```

### Exemple

```bash
java -jar spoon-instrumenter.jar \
  ../productmanagement \
  ../productmanagement-instrumented-runnable
```

---

## ⚙️ Déroulement de l’instrumentation

1. Copie complète du projet original (hors `.git/` et `target/`)
2. Analyse et transformation du code via Spoon dans un dossier temporaire
3. Injection des fichiers `*ServiceImpl.java` instrumentés
4. Ajout automatique de la dépendance `logstash-logback-encoder`
5. Création d’un fichier `logback-spring.xml` si nécessaire
6. Génération d’un projet Spring Boot **instrumenté et exécutable**

---

## ▶️ Lancer l’application instrumentée

```bash
cd productmanagement-instrumented-runnable
./mvnw -DskipTests package
./mvnw spring-boot:run
```

Les logs sont alors disponibles dans :

```
logs/app.jsonl
```

---

## 📊 Exploitation des logs

Les logs générés sont conçus pour être :
- analysés automatiquement,
- regroupés par identifiant utilisateur,
- utilisés pour construire des **profils de comportement** :
    - utilisateurs orientés lecture,
    - utilisateurs orientés écriture,
    - utilisateurs effectuant des requêtes coûteuses.

---

## ⚠️ Limites connues

- Instrumentation basée sur des heuristiques (nommage des méthodes),
- Instrumentation au niveau source (pas de bytecode),
- Granularité volontairement limitée pour un cadre pédagogique.

Ces choix visent à garantir une solution **simple, transparente et compréhensible**.

---

## 👨‍🎓 Contexte académique

Projet réalisé dans le cadre du module :

**TP Logging & Observability**  
Master Informatique – Génie Logiciel  
Université de Montpellier

---

## 📄 Licence

Projet pédagogique – usage académique uniquement.