"use client";

import type { Dictionnaire } from "@/i18n/dictionnaires";

// Le rapport arrive tel que le moteur le produit (rapport.json). Les
// champs peuvent être null : le prompt du moteur autorise explicitement
// null quand il n'est pas sûr — chaque accès est donc défensif.
type Inconnu = Record<string, unknown>;

function texte(valeur: unknown): string {
  if (valeur === null || valeur === undefined) return "—";
  return String(valeur);
}

function secondes(valeur: unknown): string {
  const n = Number(valeur);
  return Number.isFinite(n) ? `${n.toFixed(1)} s` : "—";
}

function liste(valeur: unknown): string[] {
  return Array.isArray(valeur)
    ? valeur.filter((x) => x !== null && x !== undefined).map(String)
    : [];
}

function objets(valeur: unknown): Inconnu[] {
  return Array.isArray(valeur)
    ? (valeur.filter((x) => typeof x === "object" && x !== null) as Inconnu[])
    : [];
}

function Section({
  titre,
  children,
}: {
  titre: string;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-2xl border border-bord bg-surface p-5 sm:p-6">
      <h2 className="mb-3 text-sm font-bold uppercase tracking-[0.08em] text-accent">
        {titre}
      </h2>
      {children}
    </section>
  );
}

function Mention({ d }: { d: Dictionnaire }) {
  return <p className="mt-2 text-xs italic text-faible">{d.rapport.mention}</p>;
}

/* ——— Mode A : verdict + actions ——— */
function Verdict({ generation, d }: { generation: Inconnu; d: Dictionnaire }) {
  const brut = texte(generation.verdict);
  const table: Record<string, [string, string]> = {
    pret_a_publier: [d.rapport.verdictPret, "bg-accent-fonce text-accent"],
    optimiser_avant: [d.rapport.verdictOptimiser, "bg-ambre-fonce text-ambre"],
    revoir: [d.rapport.verdictRevoir, "bg-[#3a1519] text-[#ff8a8a]"],
  };
  const [libelle, classe] = table[brut] ?? [brut, "bg-ambre-fonce text-ambre"];
  return (
    <Section titre={d.rapport.verdictTitre}>
      <span
        className={`inline-block rounded-xl px-4 py-2.5 text-xl font-extrabold ${classe}`}
      >
        {libelle}
      </span>
      <Mention d={d} />
    </Section>
  );
}

function Actions({ generation, d }: { generation: Inconnu; d: Dictionnaire }) {
  const actions = objets(generation.actions_prioritaires);
  const hooks = liste(generation.hooks_reecrits);
  const ecrans = objets(generation.texte_ecran_2_temps);
  return (
    <>
      <Section titre={d.rapport.actionsTitre}>
        <div className="flex flex-col gap-3">
          {actions.map((action, index) => {
            const critique = texte(action.priorite).toUpperCase().includes("CRIT");
            return (
              <div key={index} className="rounded-xl border border-bord bg-carte p-4">
                <div className="mb-2 flex flex-wrap items-center gap-2">
                  <span
                    className={`rounded-md px-2 py-0.5 text-xs font-bold ${
                      critique
                        ? "bg-[#3a1519] text-[#ff8a8a]"
                        : "bg-ambre-fonce text-ambre"
                    }`}
                  >
                    {texte(action.priorite)}
                  </span>
                  <span className="font-mono text-sm text-bleu tabular-nums">
                    {secondes(action.s)}
                  </span>
                </div>
                <p className="font-semibold text-white">{texte(action.action)}</p>
                <p className="mt-1.5 text-sm text-sourdine">
                  <strong className="text-encre">{d.rapport.pourquoi} : </strong>
                  {texte(action.justification)}
                </p>
                <p className="mt-1 text-sm text-faible">
                  <strong>{d.rapport.preuve} : </strong>
                  {texte(action.preuve)}
                </p>
              </div>
            );
          })}
        </div>
      </Section>

      <Section titre={d.rapport.hooksTitre}>
        <p className="mb-3 text-xs text-faible">{d.rapport.hooksAide}</p>
        <ul className="flex flex-col gap-2">
          {hooks.map((hook, index) => (
            <li
              key={index}
              className="rounded-xl border border-bord bg-carte p-3 text-lg"
              dir="auto"
            >
              {hook}
            </li>
          ))}
        </ul>
      </Section>

      {ecrans.length > 0 && (
        <Section titre={d.rapport.ecranTitre}>
          <div className="flex flex-col gap-2">
            {ecrans.map((ecran, index) => (
              <div key={index} className="flex gap-3 border-b border-dashed border-bord pb-2">
                <span className="font-mono text-sm text-bleu tabular-nums">
                  {secondes(ecran.s)}
                </span>
                <span dir="auto">{texte(ecran.texte)}</span>
              </div>
            ))}
          </div>
        </Section>
      )}

      <Section titre={d.rapport.ctaTitre}>
        <p className="font-semibold text-accent" dir="auto">
          {texte(generation.cta_fin)}
        </p>
      </Section>
    </>
  );
}

/* ——— Mode B : les 5 fiches ——— */
function Fiches({ fiches, d }: { fiches: Inconnu[]; d: Dictionnaire }) {
  return (
    <Section titre={d.rapport.fichesTitre}>
      <div className="flex flex-col gap-4">
        {fiches.map((fiche, index) => {
          const sensibilite = texte(fiche.sensibilite_plateforme);
          const sensible = /^(moyenne|haute|medium|high)/i.test(sensibilite);
          return (
            <article key={index} className="rounded-xl border border-bord bg-carte p-4">
              <p className="text-xs font-bold uppercase tracking-widest text-bleu">
                {`${index + 1} / ${fiches.length}`}
              </p>
              <h3 className="mt-1.5 text-xl font-bold text-white" dir="auto">
                {texte(fiche.titre_hook)}
              </h3>
              <p className="mt-2 text-sm text-sourdine">
                {texte(fiche.pourquoi_ca_marche)}
              </p>

              <details className="mt-3 rounded-lg border border-bord bg-surface p-3">
                <summary className="cursor-pointer text-sm font-semibold">
                  {d.rapport.scriptTitre}
                </summary>
                <p className="mt-2 whitespace-pre-wrap text-sm" dir="auto">
                  {texte(fiche.script_complet)}
                </p>
              </details>

              {objets(fiche.textes_ecran).length > 0 && (
                <div className="mt-3 flex flex-col gap-1.5">
                  {objets(fiche.textes_ecran).map((ecran, i) => (
                    <div key={i} className="flex gap-3 text-sm">
                      <span className="font-mono text-bleu tabular-nums">
                        {secondes(ecran.s)}
                      </span>
                      <span dir="auto">{texte(ecran.texte)}</span>
                    </div>
                  ))}
                </div>
              )}

              {liste(fiche.plan_de_tournage).length > 0 && (
                <>
                  <h4 className="mt-3 text-xs font-bold uppercase text-faible">
                    {d.rapport.planTitre}
                  </h4>
                  <ul className="mt-1 list-disc ps-5 text-sm text-sourdine">
                    {liste(fiche.plan_de_tournage).map((plan, i) => (
                      <li key={i}>{plan}</li>
                    ))}
                  </ul>
                </>
              )}

              <p className="mt-3 text-sm">
                <strong className="text-accent">CTA : </strong>
                <span dir="auto">{texte(fiche.cta)}</span>
              </p>
              <p className="mt-1.5 text-xs text-faible">
                {d.rapport.dureeTitre} : {secondes(fiche.duree_cible_s)} ·{" "}
                {d.rapport.sensibiliteTitre} : {sensibilite}
              </p>
              {sensible && (
                <p className="mt-2 rounded-lg bg-ambre-fonce px-3 py-2 text-xs text-ambre">
                  ⚠ {sensibilite}
                </p>
              )}
              {Boolean(fiche.actualite_requise) && (
                <p className="mt-2 rounded-lg bg-ambre-fonce px-3 py-2 text-xs text-ambre">
                  ⚠ {d.rapport.actualiteAlerte}
                </p>
              )}
            </article>
          );
        })}
      </div>
    </Section>
  );
}

/* ——— Commun : timeline, formule, transcript ——— */
function Timeline({
  ingestion,
  urlImage,
  d,
}: {
  ingestion: Inconnu;
  urlImage: string | null;
  d: Dictionnaire;
}) {
  const loudness = (ingestion.loudness ?? {}) as Inconnu;
  const puces = [
    loudness.lufs_integre !== undefined && loudness.lufs_integre !== null
      ? `${texte(loudness.lufs_integre)} LUFS`
      : null,
    `${d.rapport.cuts} : ${liste(ingestion.cuts_s).length}`,
    `${d.rapport.zonesPlates} : ${objets(ingestion.zones_plates).length}`,
    `${d.rapport.silences} : ${objets(ingestion.silences).length}`,
    ingestion.visage_present_pct !== undefined
      ? `${d.rapport.visage} : ${texte(ingestion.visage_present_pct)} %`
      : null,
  ].filter(Boolean) as string[];

  return (
    <Section titre={d.rapport.timelineTitre}>
      {urlImage && (
        /* eslint-disable-next-line @next/next/no-img-element */
        <img
          src={urlImage}
          alt={d.rapport.timelineTitre}
          className="w-full rounded-xl"
        />
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        {puces.map((puce) => (
          <span
            key={puce}
            className="rounded-full border border-bord bg-carte px-3 py-1 text-xs text-sourdine"
          >
            {puce}
          </span>
        ))}
      </div>
      <Mention d={d} />
    </Section>
  );
}

function Formule({ formule, d }: { formule: Inconnu; d: Dictionnaire }) {
  const hook = (formule.hook ?? {}) as Inconnu;
  const structure = objets(formule.structure);
  const faiblesses = objets(formule.faiblesses_observees);
  return (
    <Section titre={d.rapport.formuleTitre}>
      <p className="text-sm">
        <strong>{d.rapport.formuleFormat} : </strong>
        {texte(formule.format)}
      </p>
      <div className="mt-3 rounded-xl border border-bord bg-carte p-4">
        <p className="text-sm">
          <strong>{d.rapport.formuleHook}</strong> ({texte(hook.type)}) :{" "}
          <span dir="auto">{texte(hook.texte_ou_action)}</span>
        </p>
        <p className="mt-1 text-sm text-sourdine">
          {d.rapport.formuleAccroche} : {texte(hook.pourquoi_il_accroche)}
        </p>
      </div>

      {structure.length > 0 && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-sm">
            <tbody>
              {structure.map((acte, index) => (
                <tr key={index} className="border-b border-bord align-top">
                  <td className="py-2 pe-3 text-xs font-bold uppercase text-accent">
                    {texte(acte.acte)}
                  </td>
                  <td className="whitespace-nowrap py-2 pe-3 font-mono text-xs text-bleu tabular-nums">
                    {secondes(acte.de_s)} → {secondes(acte.a_s)}
                  </td>
                  <td className="py-2">{texte(acte.contenu)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <p className="mt-4 text-sm">
        <strong>{d.rapport.formuleEmotion} : </strong>
        {texte(formule.declencheur_emotionnel)}
      </p>
      <p className="mt-1 text-sm">
        <strong>{d.rapport.formulePartage} : </strong>
        {texte(formule.declencheur_de_partage)}
      </p>

      {liste(formule.elements_transferables).length > 0 && (
        <>
          <h4 className="mt-4 text-xs font-bold uppercase text-faible">
            {d.rapport.formuleTransferable}
          </h4>
          <ul className="mt-1 list-disc ps-5 text-sm text-sourdine">
            {liste(formule.elements_transferables).map((element, i) => (
              <li key={i}>{element}</li>
            ))}
          </ul>
        </>
      )}

      {faiblesses.length > 0 && (
        <>
          <h4 className="mt-4 text-xs font-bold uppercase text-faible">
            {d.rapport.formuleFaiblesses}
          </h4>
          <ul className="mt-1 flex flex-col gap-1.5 text-sm">
            {faiblesses.map((faiblesse, i) => (
              <li key={i}>
                <span className="font-mono text-bleu tabular-nums">
                  {secondes(faiblesse.s)}
                </span>{" "}
                {texte(faiblesse.quoi)}
                <em className="block text-xs text-faible">
                  {d.rapport.preuve} : {texte(faiblesse.preuve)}
                </em>
              </li>
            ))}
          </ul>
        </>
      )}

      <p className="mt-4 text-sm">
        <strong>{d.rapport.formuleMecanisme} : </strong>
        {texte(formule.pourquoi_ca_marche)}
      </p>
    </Section>
  );
}

/* ——— Composant principal ——— */
export default function Rapport({
  rapport,
  urlTimeline,
  d,
}: {
  rapport: Inconnu;
  urlTimeline: string | null;
  d: Dictionnaire;
}) {
  const generation = rapport.generation;
  const comprehension = (rapport.comprehension ?? {}) as Inconnu;
  const ingestion = (rapport.ingestion ?? {}) as Inconnu;
  const transcription = rapport.transcription as Inconnu | null;
  const langues = liste(comprehension.langues_detectees).join(", ") || "—";

  const segments = transcription ? objets(transcription.segments) : [];
  const transcriptTexte =
    segments.length > 0
      ? null
      : texte(comprehension.transcript) === "—"
        ? null
        : texte(comprehension.transcript);

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-faible">
        {texte(rapport.video)} · {secondes(ingestion.duree_s)} ·{" "}
        {d.rapport.langues} : {langues}
      </p>

      {Array.isArray(generation) ? (
        <Fiches fiches={objets(generation)} d={d} />
      ) : (
        <>
          <Verdict generation={(generation ?? {}) as Inconnu} d={d} />
          <Actions generation={(generation ?? {}) as Inconnu} d={d} />
        </>
      )}

      <Timeline ingestion={ingestion} urlImage={urlTimeline} d={d} />
      <Formule formule={(rapport.formule ?? {}) as Inconnu} d={d} />

      {(segments.length > 0 || transcriptTexte) && (
        <section className="rounded-2xl border border-bord bg-surface p-5 sm:p-6">
          <details>
            <summary className="cursor-pointer text-sm font-bold uppercase tracking-[0.08em] text-accent">
              {d.rapport.transcriptTitre}
            </summary>
            <div className="mt-3 flex flex-col gap-1.5 text-sm" dir="auto">
              {segments.length > 0
                ? segments.map((segment, index) => (
                    <div key={index} className="flex gap-3">
                      <span className="font-mono text-xs text-bleu tabular-nums">
                        {secondes(segment.de_s)}
                      </span>
                      <span>{texte(segment.texte)}</span>
                    </div>
                  ))
                : <p className="whitespace-pre-wrap">{transcriptTexte}</p>}
            </div>
          </details>
        </section>
      )}
    </div>
  );
}
